"""Deterministic stand-ins for the Email page's end-to-end runs: OpenRouter's /chat/completions and the Gmail API.

The model answers the Email page's forced `submit_insights` call from simple rules over the email it was given
(Portuguese, English and Spanish keywords): an intent, the sender's details, lines, a date, an amount and a short
reply. It checks plumbing and the page, not model quality. The Gmail API serves one mailbox the walkthrough fills
through hooks, so the app's real inbox sync stores the mail, and it keeps what the app sends.

    python3 fake-services.py [port] [log]     # defaults: 8099, /tmp/fake-email-services.jsonl
    # then run the app with OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1 GMAIL_API_URL=http://127.0.0.1:8099/gmail/v1

Hooks:
    POST /__gmail/reset    {"emailAddress": "obras@example.test"}   empties the mailbox and the sent mail
    POST /__gmail/receive  {"from", "subject", "text", "threadId"?, "to"?, "minutesAgo"?, "labels"?, "headers"?, "attachments"?: [name]}
    GET  /__gmail/sent     what the app sent: to, subject, threadId, inReplyTo, text
    GET  /__model/last     the last request the model received
"""
import base64
import email
import email.policy
import json
import re
import sys
import threading
import time
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else "/tmp/fake-email-services.jsonl"
LOCK = threading.Lock()
MAILBOX = {"emailAddress": "obras@example.test", "historyId": 100, "messages": {}, "order": [], "history": [], "sent": []}
STATE = {"last": None, "seq": 0}


def log(kind, body):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps({"at": time.time(), "kind": kind, "body": body}, ensure_ascii=False) + "\n")


def b64(text):
    return base64.urlsafe_b64encode(text.encode("utf-8")).decode("ascii")


# ---------- Gmail ----------

def gmail_message(mid, thread_id, sender, to, subject, text, millis, labels, headers, attachments):
    top = [{"name": "From", "value": sender}, {"name": "To", "value": to}, {"name": "Subject", "value": subject},
           {"name": "Message-ID", "value": f"<{mid}@mail.example.test>"}]
    top += [{"name": k, "value": v} for k, v in (headers or {}).items()]
    body = {"partId": "0", "mimeType": "text/plain", "filename": "", "headers": [{"name": "Content-Type", "value": 'text/plain; charset="UTF-8"'}],
            "body": {"size": len(text), "data": b64(text)}}
    files = [{"partId": str(i + 1), "mimeType": "application/pdf", "filename": name,
              "headers": [{"name": "Content-Type", "value": f'application/pdf; name="{name}"'}, {"name": "Content-Disposition", "value": f'attachment; filename="{name}"'}],
              "body": {"attachmentId": f"att-{i}", "size": 48213}} for i, name in enumerate(attachments or [])]
    if files:
        payload = {"partId": "", "mimeType": "multipart/mixed", "filename": "", "headers": top, "body": {"size": 0}, "parts": [body] + files}
    else:
        body["headers"] = top + body["headers"]
        payload = body
    return {"id": mid, "threadId": thread_id, "labelIds": labels, "snippet": text[:80], "historyId": "1",
            "internalDate": str(millis), "payload": payload, "sizeEstimate": 2048}


def receive(spec):
    with LOCK:
        STATE["seq"] += 1
        mid = spec.get("id") or f"m{int(time.time())}{STATE['seq']}"
        thread_id = spec.get("threadId") or f"t-{mid}"
        millis = int((time.time() - 60 * float(spec.get("minutesAgo", 0))) * 1000)
        message = gmail_message(mid, thread_id, spec["from"], spec.get("to") or MAILBOX["emailAddress"], spec.get("subject", ""), spec.get("text", ""),
                                millis, spec.get("labels") or ["INBOX", "UNREAD", "CATEGORY_PERSONAL"], spec.get("headers"), spec.get("attachments"))
        MAILBOX["messages"][mid] = message
        MAILBOX["order"].insert(0, mid)
        MAILBOX["historyId"] += 1
        MAILBOX["history"].append((MAILBOX["historyId"], mid))
        return {"id": mid, "threadId": thread_id}


def gmail_get(path, query):
    with LOCK:
        if path == "profile":
            return 200, {"emailAddress": MAILBOX["emailAddress"], "historyId": str(MAILBOX["historyId"])}
        if path == "history":
            start = int(query.get("startHistoryId", "0"))
            records = [{"id": str(h), "messages": [{"id": mid}], "messagesAdded": [{"message": {"id": mid, "threadId": MAILBOX["messages"][mid]["threadId"], "labelIds": ["INBOX", "UNREAD"]}}]}
                       for h, mid in MAILBOX["history"] if h > start]
            return 200, {"history": records, "historyId": str(MAILBOX["historyId"])}
        if path == "messages":
            ids = [{"id": mid, "threadId": MAILBOX["messages"][mid]["threadId"]} for mid in MAILBOX["order"]]
            return 200, {"messages": ids, "resultSizeEstimate": len(ids)}
        if path.startswith("messages/"):
            message = MAILBOX["messages"].get(path.split("/", 1)[1])
            if message:
                return 200, message
            return 404, {"error": {"code": 404, "message": "Requested entity was not found.", "errors": [{"reason": "notFound"}]}}
    return 404, {"error": {"code": 404, "message": "unknown"}}


def gmail_send(body):
    raw = base64.urlsafe_b64decode(body["raw"] + "=" * (-len(body["raw"]) % 4))
    parsed = email.message_from_bytes(raw, policy=email.policy.default)
    text = ""
    for part in parsed.walk():
        if part.get_content_type() == "text/plain":
            text = part.get_content()
            break
    with LOCK:
        STATE["seq"] += 1
        sid = f"s{STATE['seq']}"
        thread_id = body.get("threadId") or f"t-{sid}"
        MAILBOX["sent"].append({"id": sid, "threadId": thread_id, "to": str(parsed["To"] or ""), "subject": str(parsed["Subject"] or ""),
                                "inReplyTo": str(parsed["In-Reply-To"] or ""), "text": text})
    return {"id": sid, "threadId": thread_id, "labelIds": ["SENT"]}


# ---------- the model ----------

KEYWORDS = {
    "quote_reply": r"\b(aceito|aceitamos|aceitar o orçamento|accept|we accept|acepto|aceptamos)\b",
    "payment_sent": r"\b(transferência|transferi|paguei|pagamento (feito|efetuado)|comprovativo|paid|payment sent|pagado|transferencia)\b",
    "supplier_bill": r"\b(segue (em anexo )?a fatura|fatura n\.?º|invoice attached|please find (the|our) invoice|adjuntamos la factura)\b",
    "booking_request": r"\b(marcar|marcação|agendar|appointment|book a|reservar|reserva|cita)\b",
    "quote_request": r"\b(orçamento|orcamento|quote|presupuesto)\b",
    "complaint": r"\b(reclamação|reclamar|complaint|queja|insatisfeit[oa])\b",
}
SUMMARIES = {
    "quote_request": "{name} pede um orçamento: {request}.",
    "booking_request": "{name} quer fazer uma marcação.",
    "payment_sent": "{name} diz que já pagou{doc}{amount}.",
    "supplier_bill": "{name} envia a fatura{doc}{amount}{due}.",
    "quote_reply": "{name} aceita o orçamento{doc} e pergunta quando podem começar.",
    "complaint": "{name} apresenta uma reclamação.",
    "question": "{name} faz uma pergunta.",
    "other": "Email de {name}.",
}
REPLIES = {
    "quote_request": "Olá {first},\n\nObrigado pelo seu contacto. Vamos preparar o orçamento e enviamo-lo em breve.\n\nCom os melhores cumprimentos,",
    "payment_sent": "Olá {first},\n\nObrigado, vamos confirmar o pagamento.\n\nCom os melhores cumprimentos,",
    "quote_reply": "Olá {first},\n\nObrigado pela confirmação! Vamos combinar a data de início consigo.\n\nCom os melhores cumprimentos,",
    "supplier_bill": "Bom dia,\n\nObrigado, recebemos a fatura.\n\nCom os melhores cumprimentos,",
}


def email_text(request):
    users = [m.get("content") or "" for m in request.get("messages", []) if m.get("role") == "user"]
    match = re.search(r'<untrusted_content source="email">(.*?)</untrusted_content>', users[-1] if users else "", re.S)
    return match.group(1) if match else ""


def header(text, name):
    match = re.search(rf"^{name}: (.*)$", text, re.M)
    return match.group(1).strip() if match else ""


def amount_of(text):
    match = re.search(r"(\d{1,3}(?:[.\s]\d{3})*(?:,\d{2})|\d+(?:[.,]\d{2})?)\s*(?:€|eur)", text, re.I)
    if not match:
        return None
    raw = match.group(1).replace(" ", "")
    raw = raw.replace(".", "").replace(",", ".") if "," in raw else raw
    return float(raw)


def insights(request):
    text = email_text(request)
    body = text.split("\n\n", 1)[1] if "\n\n" in text else text
    low = body.lower()
    sender = header(text, "From")
    name = re.sub(r"\s*<.*?>\s*", "", sender).strip() or sender
    attachments = header(text, "Attachments")
    intent = "question" if "?" in body else "other"
    for key, pattern in KEYWORDS.items():
        if re.search(pattern, low):
            intent = key
            break
    if intent == "quote_request" and re.search(r"\b(fatura|invoice)\b", low) and attachments:
        intent = "supplier_bill"
    contact = {"name": name}
    signature = body.strip().splitlines()[-4:]
    company = next((l.strip() for l in signature if re.search(r"\b(lda|s\.a\.|unipessoal|ltd)\b", l, re.I)), None)
    if company:
        contact = {"company": company.rstrip("."), "name": None} if intent == "supplier_bill" else {"name": name, "company": company}
    phone = re.search(r"(?:\+351\s?)?(9[1236]\d|2\d{2})[\s.]?\d{3}[\s.]?\d{3}", body)
    if phone:
        contact["phone"] = phone.group(0)
    nif = re.search(r"\bNIF:?\s*(\d{9})\b", body, re.I)
    if nif:
        contact["taxId"] = nif.group(1)
    contact = {k: v for k, v in contact.items() if v}
    result = {"intent": intent, "contact": contact}
    doc = re.search(r"\b(ORC-\d{3,}|FAT-\d{3,}|FT \d{4}/\d+)\b", body)
    if doc:
        result["documentNumber"] = doc.group(1)
    amount = amount_of(body)
    if amount is not None:
        result["amount"] = amount
    due = re.search(r"vencimento (?:a |em )?(\d{1,2})/(\d{1,2})(?:/(\d{4}))?", low)
    if due:
        result["dueDate"] = f"{due.group(3) or date.today().year}-{int(due.group(2)):02d}-{int(due.group(1)):02d}"
    if intent == "quote_reply":
        result["accepted"] = True
    if intent == "quote_request":
        wanted = re.search(r"orçamento para ([^.?!\n]+)", body, re.I)
        request_text = (wanted.group(1) if wanted else body.strip().splitlines()[0]).strip().rstrip(",")
        area = re.search(r"(\d+)\s*m2", low)
        description = re.sub(r",?\s*(cerca de|aproximadamente)?\s*\d+\s*m2", "", request_text).strip()
        result["request"] = request_text[0].upper() + request_text[1:]
        result["items"] = [{"description": description[0].upper() + description[1:], **({"quantity": int(area.group(1)), "unit": "m2"} if area else {})}]
    if intent == "booking_request":
        day = re.search(r"\bdia (\d{1,2})\b", low)
        hour = re.search(r"\b(\d{1,2})h(\d{2})?\b", low)
        if day:
            today = date.today()
            target = today.replace(day=int(day.group(1)))
            if target < today:
                target = (today.replace(day=1) + timedelta(days=32)).replace(day=int(day.group(1)))
            result["date"] = target.isoformat()
        if hour:
            result["time"] = f"{int(hour.group(1)):02d}:{hour.group(2) or '00'}"
    first = (contact.get("name") or name).split()[0] if (contact.get("name") or name) else ""
    result["summary"] = SUMMARIES[intent].format(
        name=contact.get("name") or contact.get("company") or name,
        request=result.get("request", "").lower(),
        doc=f" {result['documentNumber']}" if "documentNumber" in result else "",
        amount=f" de {amount:.2f} €".replace(".", ",") if amount is not None else "",
        due=f", com vencimento a {result['dueDate']}" if "dueDate" in result else "",
    )
    if intent in REPLIES:
        result["reply"] = REPLIES[intent].format(first=first)
    return result


def completion(request):
    tools = [t["function"]["name"] for t in request.get("tools") or []]
    if "submit_insights" in tools:
        args = insights(request)
        message = {"role": "assistant", "content": None, "tool_calls": [{"id": f"call_{int(time.time() * 1000)}", "type": "function",
                                                                          "function": {"name": "submit_insights", "arguments": json.dumps(args, ensure_ascii=False)}}]}
        finish = "tool_calls"
    else:
        message = {"role": "assistant", "content": "ok"}
        finish = "stop"
    return {"id": f"fake-{int(time.time() * 1000)}", "choices": [{"message": message, "finish_reason": finish}],
            "usage": {"prompt_tokens": 900, "completion_tokens": 120, "total_tokens": 1020}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        path, _, raw = self.path.partition("?")
        query = dict(p.split("=", 1) for p in raw.split("&") if "=" in p)
        if path.startswith("/gmail/v1/users/me/"):
            status, body = gmail_get(path[len("/gmail/v1/users/me/"):], query)
            return self.reply(status, body)
        if path == "/__gmail/sent":
            with LOCK:
                return self.reply(200, list(MAILBOX["sent"]))
        if path == "/__model/last":
            return self.reply(200, STATE["last"] or {})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        body = self.read()
        if self.path.startswith("/api/v1/chat/completions"):
            STATE["last"] = body
            answer = completion(body)
            log("model", {"request": body, "answer": answer})
            return self.reply(200, answer)
        if self.path == "/gmail/v1/users/me/messages/send":
            sent = gmail_send(body)
            log("gmail.send", sent)
            return self.reply(200, sent)
        if self.path == "/__gmail/reset":
            with LOCK:
                MAILBOX.update({"emailAddress": body.get("emailAddress", MAILBOX["emailAddress"]), "messages": {}, "order": [], "history": [], "sent": []})
            return self.reply(200, {"ok": True})
        if self.path == "/__gmail/receive":
            return self.reply(200, receive(body))
        self.reply(404, {"error": "not found"})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print(f"Fake OpenRouter and Gmail on http://127.0.0.1:{port} (log: {LOG})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
