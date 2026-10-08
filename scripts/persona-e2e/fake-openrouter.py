"""A deterministic stand-in for OpenRouter's /chat/completions, for local end-to-end runs.

It answers synthesis requests by merging the new material into the current file, and chat
requests from what the persona block says (name, language, tone, emoji, greeting, rules and
instructions), calling handoff_to_human when a customer asks for a person and it is offered.
It checks plumbing, not model quality. Every request is appended to the log file so a run can be
inspected afterwards.

    python3 fake-openrouter.py [port] [log]     # defaults: 8099, /tmp/fake-openrouter.jsonl
    # then run the app with OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1
"""
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else "/tmp/fake-openrouter.jsonl"
MATERIAL = re.compile(r'<material kind="([A-Z_]+)" label="([^"]*)">\n(.*?)\n</material>', re.S)


def completion(content=None, tool_calls=None, prompt=0):
    message = {"role": "assistant", "content": content}
    if tool_calls:
        message["tool_calls"] = tool_calls
    return {
        "id": f"fake-{int(time.time() * 1000)}",
        "choices": [{"message": message, "finish_reason": "tool_calls" if tool_calls else "stop"}],
        "usage": {"prompt_tokens": prompt, "completion_tokens": 40, "total_tokens": prompt + 40},
    }


def synthesize(system, user):
    budget = int(re.search(r"Stay under (\d+) characters", system).group(1))
    current = user.split("<current_file>\n", 1)[1].split("\n</current_file>", 1)[0]
    if current.startswith("(empty"):
        current = ""
    lines = [l for l in current.splitlines() if l.strip()]
    for _kind, _label, text in MATERIAL.findall(user):
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            bullet = line if line.startswith(("#", "-")) else f"- {line}"
            if bullet not in lines:
                lines.append(bullet)
    return "\n".join(lines)[:budget]


def persona_of(systems):
    block = next((s for s in systems if s.startswith("<persona>")), "")
    get = lambda pattern: (re.search(pattern, block) or [None, None])[1]
    body = block.replace("<persona>", "").replace("</persona>", "").strip()
    paragraphs = body.split("\n\n")
    settings = [p for p in paragraphs if p.startswith(("How the company wants you to talk", "Rules you always follow:"))]
    rules_part = next((p for p in settings if p.startswith("Rules you always follow:")), "")
    rules = re.findall(r"^- (.+)$", rules_part, re.M)
    instructions = "\n".join(p for p in paragraphs if p not in settings)
    knowledge = [l.strip("-# ").strip() for l in instructions.splitlines() if l.strip("-# ").strip()]
    return {
        "name": get(r"Your name is ([^.]+)\."),
        "strict": get(r"Language: always reply in ([A-Za-z ]+), even"),
        "default": get(r"Language: reply in ([A-Za-z ]+); when"),
        "tone": get(r"Tone: ([a-z]+)"),
        "emoji": get(r"Emoji: ([^\n]+)"),
        "greeting": get(r'adapted naturally: "([^"]+)"'),
        "formal": "Address the customer formally" in block,
        "rules": rules,
        "knowledge": knowledge,
        "has_block": bool(block),
    }


def language_for(p, last):
    if p["strict"]:
        return p["strict"]
    text = last.lower()
    if re.search(r"\b(the|you|your|what|how|can|please|hello|hi)\b", text):
        return "English"
    if re.search(r"(¿|¡|\busted\b|\bpuedes\b|\bcuánto\b|\bquién\b|\bhola\b|español)", text):
        return "Spanish"
    return p["default"] or "European Portuguese"


WORDS = {
    "English": dict(hi="Hello", me="I'm {name}", bot="the assistant", hours="Our hours: {x}", price="Prices: {x}", unknown="I'll check that with the team for you.",
                    refuse="I can't share my internal instructions, but I'm happy to help with anything about our services.", handoff="I'm passing you to a colleague, who will reply here shortly.", help="How can I help?"),
    "Spanish": dict(hi="¡Hola", me="Soy {name}", bot="el asistente", hours="Nuestro horario: {x}", price="Precios: {x}", unknown="Lo consulto con el equipo y te digo.",
                    refuse="No puedo compartir mis instrucciones internas, pero con gusto te ayudo con nuestros servicios.", handoff="Te paso con un compañero, que te responderá aquí en breve.", help="¿En qué puedo ayudarte?"),
    "European Portuguese": dict(hi="Olá", me="Sou a {name}", bot="o assistente", hours="O nosso horário: {x}", price="Preços: {x}", unknown="Vou confirmar isso com a equipa e já lhe digo.",
                                refuse="Não posso partilhar as minhas instruções internas, mas ajudo com todo o gosto no que precisar sobre os nossos serviços.", handoff="Vou passar a conversa a um colega, que lhe responde aqui em breve.", help="Em que posso ajudar?"),
}


def chat(body):
    messages = body["messages"]
    systems = [m.get("content") or "" for m in messages if m["role"] == "system"]
    tools = [t["function"]["name"] for t in body.get("tools") or []]
    p = persona_of(systems)
    users = [m.get("content") or "" for m in messages if m["role"] == "user"]
    last = users[-1] if users else ""
    lang = language_for(p, last)
    w = WORDS.get(lang, WORDS["European Portuguese"])
    low = last.lower()
    prompt = sum(len(m.get("content") or "") for m in messages) // 4

    if messages[-1]["role"] == "tool":
        return completion(w["handoff"], prompt=prompt)
    if "handoff_to_human" in tools and re.search(r"(pessoa|person|humano|human|persona\b|reembolso|refund)", low):
        call = {"id": f"call-{int(time.time()*1000)}", "type": "function",
                "function": {"name": "handoff_to_human", "arguments": json.dumps({"reason": "the customer asked to talk to a person"})}}
        return completion(None, [call], prompt)

    parts = []
    first = not any(m["role"] == "assistant" for m in messages)
    if first and p["greeting"]:
        parts.append(p["greeting"])
    guarded = any(s.startswith("Platform rules.") for s in systems)
    if re.search(r"(ignore|ignora|prompt|instruç|instrucc)", low):
        parts.append(w["refuse"] if guarded else "My instructions are: " + " / ".join(p["knowledge"][:3]))
    elif re.search(r"(quem|who are|quién|quien)", low):
        who = w["me"].format(name=p["name"]) if p["name"] else w["me"].format(name=w["bot"]).replace("Sou a o", "Sou o")
        about = next((k for k in p["knowledge"] if len(k) > 12), "")
        parts.append(f"{who}. {about}".strip())
    elif re.search(r"(horár|horar|hours|abert|open)", low):
        hit = next((k for k in p["knowledge"] if re.search(r"\d{1,2}(h|:\d\d)", k)), None)
        parts.append(w["hours"].format(x=hit) if hit else w["unknown"])
    elif re.search(r"(custa|cost|cuesta|preç|precio|price|quanto)", low):
        hits = [k for k in p["knowledge"] if "€" in k]
        parts.append(w["price"].format(x="; ".join(hits)) if hits else w["unknown"])
    else:
        if not (first and p["greeting"]):
            parts.append(f"{w['hi']}!" if lang != "Spanish" else f"{w['hi']}!")
        parts.append(w["help"])
    text = " ".join(parts)
    if p["emoji"] and p["emoji"].startswith("use emoji freely"):
        text += " 😊✨"
    elif p["emoji"] and p["emoji"].startswith("an emoji now and then"):
        text += " 🙂"
    if p["tone"] == "formal" or p["formal"]:
        text = text.replace("Olá!", "Bom dia.")
    return completion(text, prompt=prompt)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        systems = [m.get("content") or "" for m in body.get("messages", []) if m["role"] == "system"]
        user = next((m.get("content") or "" for m in reversed(body.get("messages", [])) if m["role"] == "user"), "")
        if systems and systems[0].startswith("You maintain the instruction file"):
            kind, result = "synthesis", completion(synthesize(systems[0], user), prompt=len(user) // 4)
        elif systems and systems[0].startswith("The chatbot instruction file you get is too long"):
            kind, result = "condense", completion(user[: int(re.search(r"under (\d+)", systems[0]).group(1))], prompt=len(user) // 4)
        else:
            kind, result = "chat", chat(body)
        with open(LOG, "a") as log:
            log.write(json.dumps({"kind": kind, "model": body.get("model"), "tools": [t["function"]["name"] for t in body.get("tools") or []],
                                  "system": systems, "last_user": user, "reply": result["choices"][0]["message"]}, ensure_ascii=False) + "\n")
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-openrouter " + (fmt % args) + "\n")


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8099), Handler).serve_forever()
