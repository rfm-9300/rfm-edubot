"""A deterministic stand-in for OpenRouter's /chat/completions, for end-to-end runs of the dashboard AI assistant.

It reads a request the way the assistant's model would: it calls the tools the request offers (searches,
lists, proposals of writes) from simple intent rules in Portuguese, English and Spanish, and summarises
tool results in markdown (bold, lists and tables). It checks plumbing and UI, not model quality. Every
request and answer is appended to the log file, so a run can be inspected afterwards.

    python3 fake-openrouter.py [port] [log]     # defaults: 8099, /tmp/fake-openrouter.jsonl
    # then run the app with OPENROUTER_BASE_URL=http://127.0.0.1:8099/api/v1

Test hooks:
    POST /__fail {"count": 2}     the next 2 completions answer HTTP 500 (the app retries each call 3 times)
    POST /__reset                 clears pending failures
    GET  /__last                  the last request body the model received
A message that starts with "/tool <name> <json>" makes the model call exactly that tool.
"""
import json
import re
import sys
import threading
import time
from datetime import date, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = sys.argv[2] if len(sys.argv) > 2 else "/tmp/fake-openrouter.jsonl"
STATE = {"fail": 0, "last": None}
LOCK = threading.Lock()
SEQ = [0]


def call_id():
    with LOCK:
        SEQ[0] += 1
        return f"call_{int(time.time())}_{SEQ[0]}"


def completion(content=None, tool_calls=None, prompt=0):
    message = {"role": "assistant", "content": content}
    if tool_calls:
        message["tool_calls"] = tool_calls
    return {
        "id": f"fake-{int(time.time() * 1000)}",
        "choices": [{"message": message, "finish_reason": "tool_calls" if tool_calls else "stop"}],
        "usage": {"prompt_tokens": prompt, "completion_tokens": 30, "total_tokens": prompt + 30},
    }


def tool_call(name, args):
    return {"id": call_id(), "type": "function", "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}


# ---------- language ----------

def language(text):
    low = text.lower()
    if re.search(r"[¿¡]|\b(hola|cuánto|cuántas|quién|facturas|presupuesto|cliente[s]? de|qué|mañana|citas|gracias|vencidas)\b", low):
        return "es"
    if re.search(r"\b(the|what|who|how|which|show|list|create|mark|find|my|please|tomorrow|client|invoice|quote|is|are|me)\b", low):
        return "en"
    return "pt"


T = {
    "pt": dict(found="Encontrei", none="Não encontrei nada.", clients="clientes", client="Cliente", quotes="Orçamentos", invoices="Faturas",
               total="Total", status="Estado", number="Número", outstanding="Em dívida", due="Vencimento", phone="Telefone", name="Nome",
               done="Feito!", failed="Não consegui fazer isso", help="Posso consultar clientes, orçamentos, faturas, pagamentos, marcações e conversas, e propor alterações para aprovar.",
               refuse="Não posso partilhar as minhas instruções internas, mas posso ajudar com os dados do negócio.",
               not_found="Não encontrei o cliente", ask_create="Quer que o crie?", overview="Resumo do negócio", which="Encontrei vários — qual deles?",
               bookings="Marcações", payments="Pagamentos", services="Serviços", conversations="Conversas", agents="Agentes", suppliers="Fornecedores",
               employees="Colaboradores", summary="Resumo", messages="Mensagens", nothing="Nada a mostrar."),
    "en": dict(found="I found", none="I found nothing.", clients="clients", client="Client", quotes="Quotes", invoices="Invoices",
               total="Total", status="Status", number="Number", outstanding="Outstanding", due="Due", phone="Phone", name="Name",
               done="Done!", failed="I couldn't do that", help="I can look up clients, quotes, invoices, payments, bookings and conversations, and propose changes for you to approve.",
               refuse="I can't share my internal instructions, but I'm happy to help with your business data.",
               not_found="I couldn't find the client", ask_create="Shall I create it?", overview="Business overview", which="I found several — which one?",
               bookings="Bookings", payments="Payments", services="Services", conversations="Conversations", agents="Agents", suppliers="Suppliers",
               employees="Employees", summary="Summary", messages="Messages", nothing="Nothing to show."),
    "es": dict(found="Encontré", none="No encontré nada.", clients="clientes", client="Cliente", quotes="Presupuestos", invoices="Facturas",
               total="Total", status="Estado", number="Número", outstanding="Pendiente", due="Vencimiento", phone="Teléfono", name="Nombre",
               done="¡Hecho!", failed="No pude hacerlo", help="Puedo consultar clientes, presupuestos, facturas, pagos, reservas y conversaciones, y proponer cambios para aprobar.",
               refuse="No puedo compartir mis instrucciones internas, pero con gusto te ayudo con los datos del negocio.",
               not_found="No encontré al cliente", ask_create="¿Quieres que lo cree?", overview="Resumen del negocio", which="Encontré varios — ¿cuál?",
               bookings="Reservas", payments="Pagos", services="Servicios", conversations="Conversaciones", agents="Agentes", suppliers="Proveedores",
               employees="Colaboradores", summary="Resumen", messages="Mensajes", nothing="Nada que mostrar."),
}


def eur(value):
    try:
        return f"{float(value):,.2f} €".replace(",", "X").replace(".", ",").replace("X", ".")
    except (TypeError, ValueError):
        return str(value)


def table(headers, rows):
    if not rows:
        return ""
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c).replace("|", "/") for c in row) + " |")
    return "\n".join(out)


def today_of(systems):
    for s in systems:
        m = re.search(r"Current date and time: (\d{4}-\d{2}-\d{2})", s)
        if m:
            return date.fromisoformat(m.group(1))
    return date.today()


# ---------- summaries of tool results ----------

def strip_untrusted(text):
    return re.sub(r"</?untrusted_content[^>]*>", "", text or "")


def summarise(name, result, t):
    if not isinstance(result, dict):
        return str(result)
    if "error" in result:
        message = result.get("message") or result.get("error")
        return f"{t['failed']}: {message}"
    if "clients" in result:
        rows = [[f"**{c.get('name')}**", c.get("number", ""), c.get("phone", "")] for c in result["clients"]]
        if not rows:
            return t["none"]
        return f"{t['found']} {len(rows)} {t['clients']}:\n\n" + table([t["name"], t["number"], t["phone"]], rows)
    if "client" in result and isinstance(result["client"], dict):
        c = result["client"]
        lines = [f"**{c.get('name')}** ({c.get('number', '')})", f"- {t['phone']}: {c.get('phone', '')}"]
        for key in ("email", "tax_id", "address", "city", "notes"):
            if c.get(key):
                lines.append(f"- {key}: {c[key]}")
        money = result.get("money") or {}
        if money:
            lines.append(f"- {t['outstanding']}: {eur(money.get('outstanding_eur', 0))}")
        return "\n".join(lines)
    if "invoices" in result:
        rows = [[inv.get("number"), inv.get("client_name", inv.get("client_id", "")), inv.get("status"), eur(inv.get("total_eur")), eur(inv.get("outstanding_eur", 0)), inv.get("due_date", "")] for inv in result["invoices"]]
        if not rows:
            return t["none"]
        body = table([t["number"], t["client"], t["status"], t["total"], t["outstanding"], t["due"]], rows)
        summary = result.get("summary") or {}
        tail = f"\n\n**{t['total']}:** {eur(summary.get('total_eur'))} · **{t['outstanding']}:** {eur(summary.get('outstanding_eur'))}" if summary else ""
        return f"**{t['invoices']}** ({len(rows)})\n\n{body}{tail}"
    if "quotes" in result:
        rows = [[q.get("number"), q.get("client_name", q.get("client_id", "")), q.get("status"), eur(q.get("total_eur"))] for q in result["quotes"]]
        if not rows:
            return t["none"]
        return f"**{t['quotes']}** ({len(rows)})\n\n" + table([t["number"], t["client"], t["status"], t["total"]], rows)
    if "overview" in result:
        o = result["overview"]
        lines = [f"### {t['overview']}"]
        for key, value in o.items():
            if isinstance(value, dict):
                parts = ", ".join(f"{k}: {eur(v) if k.endswith('_eur') else v}" for k, v in value.items() if not isinstance(v, (list, dict)))
                lines.append(f"- **{key}**: {parts}")
            elif not isinstance(value, list):
                lines.append(f"- **{key}**: {value}")
        attention = result.get("attention") or o.get("attention") or []
        for item in attention[:5]:
            if isinstance(item, dict):
                lines.append(f"  - ⚠️ {strip_untrusted(item.get('detail', ''))}")
        return "\n".join(lines)
    if "bookings" in result:
        rows = [[b.get("start_local", "").replace("T", " "), b.get("service_name", ""), b.get("contact_name", ""), b.get("status", "")] for b in result["bookings"]]
        return f"**{t['bookings']}** ({len(rows)})\n\n" + table(["", "", "", t["status"]], rows) if rows else t["none"]
    if "payments" in result:
        rows = [[p.get("number"), p.get("payee", ""), p.get("status"), eur(p.get("amount_eur")), p.get("due_date", "")] for p in result["payments"]]
        return f"**{t['payments']}** ({len(rows)})\n\n" + table([t["number"], "", t["status"], t["total"], t["due"]], rows) if rows else t["none"]
    if "services" in result:
        rows = [[s.get("name"), s.get("client_name", ""), s.get("status"), eur(s.get("total_eur"))] for s in result["services"]]
        return f"**{t['services']}** ({len(rows)})\n\n" + table([t["name"], t["client"], t["status"], t["total"]], rows) if rows else t["none"]
    if "conversations" in result:
        rows = [[strip_untrusted(c.get("contact", "")), c.get("channel", ""), "⏳" if c.get("waiting") else "", strip_untrusted(c.get("last_message", ""))[:40]] for c in result["conversations"]]
        return f"**{t['conversations']}** ({len(rows)})\n\n" + table([t["name"], "", "", ""], rows) if rows else t["none"]
    if "messages" in result:
        lines = [f"**{t['messages']}** — {strip_untrusted(result.get('contact', ''))}"]
        for m in result["messages"][-6:]:
            lines.append(f"- {m.get('from')}: {strip_untrusted(m.get('text', ''))[:80]}")
        return "\n".join(lines)
    if "agents" in result:
        rows = [[a.get("name"), a.get("status")] for a in result["agents"]]
        return f"**{t['agents']}** ({len(rows)})\n\n" + table([t["name"], t["status"]], rows) if rows else t["none"]
    for key, label in (("suppliers", "suppliers"), ("employees", "employees")):
        if key in result:
            rows = [[s.get("name"), s.get("number", ""), s.get("phone", "")] for s in result[key]]
            return f"**{t[label]}** ({len(rows)})\n\n" + table([t["name"], t["number"], t["phone"]], rows) if rows else t["none"]
    if "items" in result:
        rows = [[i.get("code", ""), i.get("title"), eur(i.get("default_unit_price_eur"))] for i in result["items"]]
        return table(["", t["name"], t["total"]], rows) if rows else t["none"]
    if result.get("created") or result.get("updated") or result.get("ok") or result.get("sent"):
        what = result.get("number") or result.get("name") or result.get("id") or ""
        return f"{t['done']} {what}".strip()
    return t["done"]


# ---------- intents ----------

NAME_AFTER = r"(?:cliente|client|clienta)\s+([A-ZÁÉÍÓÚÂÊÔÃÕÇ][\wÀ-ÿ'-]+(?:\s+[A-ZÁÉÍÓÚÂÊÔÃÕÇ][\wÀ-ÿ'-]+)*)"
PRICE = r"(\d+(?:[.,]\d{1,2})?)\s*(?:€|eur|euros?)"


def offered(tools, name):
    return name in tools


def find(pattern, text, group=1, flags=re.I):
    m = re.search(pattern, text, flags)
    return m.group(group).strip() if m else None


def turn_calls(messages):
    """The tool calls and results made since the last user message: [(name, args, result)]."""
    start = max(i for i, m in enumerate(messages) if m["role"] == "user")
    calls, results = {}, {}
    order = []
    for m in messages[start + 1:]:
        for c in m.get("tool_calls") or []:
            calls[c["id"]] = (c["function"]["name"], json.loads(c["function"]["arguments"] or "{}"))
            order.append(c["id"])
        if m["role"] == "tool":
            try:
                results[m.get("tool_call_id")] = json.loads(m.get("content") or "{}")
            except json.JSONDecodeError:
                results[m.get("tool_call_id")] = {"raw": m.get("content")}
    return [(calls[i][0], calls[i][1], results.get(i)) for i in order]


def plan(last, tools, done, today, t):
    """The next step for the user's request: ("tool", name, args) | ("text", content)."""
    low = last.lower()
    names = [n for n, _, _ in done]
    result_of = {n: r for n, _, r in done}

    directive = re.match(r"^/tool\s+([a-z_]+)\s*(\{.*\})?\s*$", last.strip(), re.S)
    if directive:
        if done:
            return None
        return ("tool", directive.group(1), json.loads(directive.group(2) or "{}"))

    if re.search(r"(ignor[ea]|instruç|instrucc|system prompt|prompt do sistema|reveal|revela)", low):
        return ("text", t["refuse"])

    # A client by name, then what the user wants with them.
    client_name = find(NAME_AFTER, last, flags=0) or find(r"(?:para|for|para el|para la|de|of)\s+(?:a |o |la |el )?([A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+(?:\s+[A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+)*)", last, flags=0)

    def with_client(next_step):
        if "search_clients" not in names:
            if not offered(tools, "search_clients") or not client_name:
                return None
            return ("tool", "search_clients", {"query": client_name})
        found = (result_of.get("search_clients") or {}).get("clients") or []
        if not found:
            return ("text", f"{t['not_found']} **{client_name}**. {t['ask_create']}")
        if len(found) > 1:
            exact = [c for c in found if c.get("name", "").lower() == (client_name or "").lower()]
            if len(exact) != 1:
                return ("text", t["which"] + "\n\n" + "\n".join(f"- **{c['name']}** ({c.get('number')})" for c in found))
            found = exact
        return next_step(found[0])

    if re.search(r"(cria|criar|create|crea|novo|nueva|nuevo|new)\b.*\b(cliente|client)", low) and offered(tools, "create_client") and "create_client" not in names:
        name = find(r"(?:cliente|client)\s+(?:chamad[oa]\s+|named\s+|llamad[oa]\s+)?([A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+(?:\s+[A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+)*)", last, flags=0) or "Cliente"
        phone = find(r"(\+?\d[\d\s]{7,}\d)", last) or ""
        args = {"name": name, "phone": phone}
        email = find(r"([\w.+-]+@[\w-]+\.[\w.]+)", last)
        if email:
            args["email"] = email
        nif = find(r"(?:nif|tax id|nie|cif)\s*:?\s*(\d{9})", last)
        if nif:
            args["tax_id"] = nif
        address = find(r"(?:morada|address|dirección)\s+(.+?)(?=\s+(?:em|in|en|nif|tax|com|with)\b|,|$)", last)
        if address:
            args["address"] = address
        city = find(r"(?:em|in|en)\s+([A-ZÁÉÍÓÚ][\wÀ-ÿ]+)\s*$", last, flags=0)
        if city:
            args["city"] = city
        return ("tool", "create_client", args)

    if re.search(r"(orçamento|orcamento|quote|presupuesto)", low) and re.search(r"(cria|criar|create|crea|faz|make|novo|new|prepara)", low):
        def quote(client):
            if "create_quote" in names or not offered(tools, "create_quote"):
                return None
            price = find(PRICE, last) or "100"
            desc = find(r"(?:com|with|con|de)\s+(?:um |uma |a |an )?([\wÀ-ÿ ]+?)\s+(?:por|for|a|at|de)\s+\d", last) or "Serviço"
            return ("tool", "create_quote", {"client_id": client["id"], "items": [{"description": desc.strip().capitalize(), "quantity": 1, "price_eur": float(price.replace(",", "."))}]})
        return with_client(quote)

    if re.search(r"(fatura|factura|invoice).*(orçamento|orcamento|quote|presupuesto)", low) or re.search(r"(converte|convert|convierte|passa).*(orc|orç|quote|presup)", low):
        number = find(r"\b(ORC-\d+)\b", last, flags=re.I)
        due = find(r"(\d{4}-\d{2}-\d{2})", last) or (today + timedelta(days=30)).isoformat()
        if number and offered(tools, "convert_quote_to_invoice") and "convert_quote_to_invoice" not in names:
            return ("tool", "convert_quote_to_invoice", {"quote_id": number.upper(), "due_date": due})

    if re.search(r"(marca|mark|marcar).*(pag[ao]|paid|pagad)", low):
        number = find(r"\b(FAT-\d+|PAG-\d+)\b", last, flags=re.I)
        if number and number.upper().startswith("PAG") and offered(tools, "list_payments"):
            if "list_payments" not in names:
                return ("tool", "list_payments", {})
            match = [p for p in (result_of["list_payments"] or {}).get("payments", []) if p.get("number", "").upper() == number.upper()]
            if match and offered(tools, "mark_payment_paid") and "mark_payment_paid" not in names:
                return ("tool", "mark_payment_paid", {"payment_id": match[0]["id"]})
        elif number and offered(tools, "list_invoices"):
            if "list_invoices" not in names:
                return ("tool", "list_invoices", {})
            match = [i for i in (result_of["list_invoices"] or {}).get("invoices", []) if i.get("number", "").upper() == number.upper()]
            if match and offered(tools, "mark_invoice_paid") and "mark_invoice_paid" not in names:
                return ("tool", "mark_invoice_paid", {"invoice_id": match[0]["id"]})

    if re.search(r"(muda|mudar|altera|change|update|cambia|actualiza).*(email|e-mail|telefone|phone|teléfono|morada|address|nif)", low):
        def update(client):
            if "update_client" in names or not offered(tools, "update_client"):
                return None
            args = {"client_id": client["id"]}
            email = find(r"([\w.+-]+@[\w-]+\.[\w.]+)", last)
            phone = find(r"(?:telefone|phone|teléfono)\D*(\+?\d[\d\s]{7,}\d)", last)
            if email:
                args["email"] = email
            if phone:
                args["phone"] = phone
            return ("tool", "update_client", args)
        return with_client(update)

    if re.search(r"(detalhe|details|detalles|ficha|tell me about|fala-me|quem é|who is|quién es)", low):
        def details(client):
            if "get_client" in names or not offered(tools, "get_client"):
                return None
            return ("tool", "get_client", {"client_id": client["id"]})
        step = with_client(details)
        if step:
            return step

    if re.search(r"(responde|reply|responder|answer).*(:|dizendo|saying|diciendo)", low) and offered(tools, "list_conversations"):
        who = find(r"(?i:responde|reply|responder|answer)\s+(?i:à|ao|a|to)\s+([A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+)", last, flags=0)
        text = find(r"[:]\s*(.+)$", last) or find(r"(?:dizendo|saying|diciendo)\s+(.+)$", last) or ""
        if "list_conversations" not in names:
            return ("tool", "list_conversations", {"query": who or ""})
        convs = (result_of["list_conversations"] or {}).get("conversations", [])
        if convs and offered(tools, "reply_to_conversation") and "reply_to_conversation" not in names:
            return ("tool", "reply_to_conversation", {"conversation_id": convs[0]["id"], "text": text.strip()})

    if re.search(r"(resume|resumo da conversa|summari[sz]e|resumen de la conversación).*(convers|chat)", low) and offered(tools, "list_conversations"):
        who = find(r"(?:com|with|con)\s+(?:a |o |la |el )?([A-ZÁÉÍÓÚ][\wÀ-ÿ'-]+)", last, flags=0) or ""
        if "list_conversations" not in names:
            return ("tool", "list_conversations", {"query": who})
        convs = (result_of["list_conversations"] or {}).get("conversations", [])
        if convs and offered(tools, "get_conversation") and "get_conversation" not in names:
            return ("tool", "get_conversation", {"conversation_id": convs[0]["id"]})

    if re.search(r"(como está|como vai|resumo do negócio|how is|how's|overview|business doing|atenção|attention|cómo va|resumen del negocio|precisa de mim|needs me)", low):
        if offered(tools, "get_business_overview") and "get_business_overview" not in names:
            return ("tool", "get_business_overview", {})
        if not offered(tools, "get_business_overview") and offered(tools, "sum_invoices_by_client") and "sum_invoices_by_client" not in names:
            return ("tool", "sum_invoices_by_client", {})

    if re.search(r"(fatura|factura|invoice)", low) and offered(tools, "list_invoices") and "list_invoices" not in names:
        args = {}
        if re.search(r"(atras|overdue|vencid)", low):
            args["status"] = "OVERDUE"
        elif re.search(r"(pendente|unpaid|por pagar|pending|pendiente)", low):
            args["status"] = "PENDING"
        elif re.search(r"(pag[ao]s\b|paid\b|pagadas)", low):
            args["status"] = "PAID"
        if re.search(r"(este mês|this month|este mes)", low):
            args["issued_from"] = today.replace(day=1).isoformat()
            args["issued_to"] = today.isoformat()
        return ("tool", "list_invoices", args)

    if re.search(r"(orçamentos|orcamentos|quotes|presupuestos)", low) and offered(tools, "list_quotes") and "list_quotes" not in names:
        args = {}
        if re.search(r"(pendente|pending|pendiente)", low):
            args["status"] = "PENDENTE"
        return ("tool", "list_quotes", args)

    if re.search(r"(marcaç|marcac|booking|agenda|reserva|citas|appointments)", low) and offered(tools, "list_bookings") and "list_bookings" not in names:
        day = today + timedelta(days=1) if re.search(r"(amanhã|amanha|tomorrow|mañana)", low) else today
        span = 7 if re.search(r"(semana|week)", low) else 1
        return ("tool", "list_bookings", {"from": f"{day.isoformat()}T00:00", "to": f"{(day + timedelta(days=span)).isoformat()}T00:00"})

    if re.search(r"(conversa|chats?\b|mensagens|messages|waiting for a reply|à espera|esperando)", low) and offered(tools, "list_conversations") and "list_conversations" not in names:
        return ("tool", "list_conversations", {"waiting_only": bool(re.search(r"(espera|waiting|responder|reply|sin respuesta)", low))})

    if re.search(r"(pagamento|payment|pagos|despesa|expense)", low) and offered(tools, "list_payments") and "list_payments" not in names:
        args = {"status": "OVERDUE"} if re.search(r"(atras|overdue|vencid)", low) else {}
        return ("tool", "list_payments", args)

    if re.search(r"(serviços|servicos|services|servicios|trabalho por faturar|unbilled|open work)", low) and offered(tools, "list_services") and "list_services" not in names:
        return ("tool", "list_services", {"status": "OPEN"})

    if re.search(r"(fornecedor|supplier|proveedor)", low) and offered(tools, "list_suppliers") and "list_suppliers" not in names:
        return ("tool", "list_suppliers", {"query": ""})

    if re.search(r"(colaborador|employee|empleado|equipa|team)", low) and offered(tools, "list_employees") and "list_employees" not in names:
        return ("tool", "list_employees", {"query": ""})

    if re.search(r"(agente|agent|automaç|automation)", low) and offered(tools, "list_agents") and "list_agents" not in names:
        return ("tool", "list_agents", {})

    if re.search(r"(clientes|clients)\b", low) and offered(tools, "search_clients") and "search_clients" not in names:
        return ("tool", "search_clients", {"query": client_name or ""})

    if client_name and offered(tools, "search_clients") and "search_clients" not in names:
        return ("tool", "search_clients", {"query": client_name})

    return None


def assistant(body):
    messages = body["messages"]
    systems = [m.get("content") or "" for m in messages if m["role"] == "system"]
    tools = [t["function"]["name"] for t in body.get("tools") or []]
    prompt = sum(len(m.get("content") or "") for m in messages) // 4
    users = [m.get("content") or "" for m in messages if m["role"] == "user"]
    last_user = users[-1] if users else ""
    t = T[language(last_user)]
    today = today_of(systems)
    last = messages[-1]

    # The person confirmed a proposed change: report what happened, from the last tool result.
    if last["role"] == "system" and re.search(r"confirm", last.get("content") or "", re.I):
        tool_results = [m for m in messages if m["role"] == "tool"]
        try:
            result = json.loads(tool_results[-1].get("content") or "{}") if tool_results else {}
        except json.JSONDecodeError:
            result = {}
        return completion(summarise("", result, t), prompt=prompt)

    done = turn_calls(messages) if any(m["role"] == "user" for m in messages) else []
    step = plan(last_user, tools, done, today, t)
    if step and step[0] == "tool":
        return completion(None, [tool_call(step[1], step[2])], prompt)
    if step and step[0] == "text":
        return completion(step[1], prompt=prompt)
    if done:
        return completion("\n\n".join(summarise(n, r, t) for n, _, r in done if r is not None), prompt=prompt)
    if re.search(r"^(olá|ola|oi|hello|hi|hola|bom dia|good morning|buenos días)\b", last_user.lower()):
        return completion(f"**{t['help']}**", prompt=prompt)
    return completion(t["help"], prompt=prompt)


def other(body):
    return completion("OK.", prompt=sum(len(m.get("content") or "") for m in body["messages"]) // 4)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/__last":
            return self.reply(200, STATE["last"] or {})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        body = json.loads(raw or b"{}")
        if self.path == "/__fail":
            STATE["fail"] = int(body.get("count", 1))
            return self.reply(200, {"fail": STATE["fail"]})
        if self.path == "/__reset":
            STATE["fail"] = 0
            return self.reply(200, {"fail": 0})
        if not self.path.endswith("/chat/completions"):
            return self.reply(404, {"error": "not found"})
        STATE["last"] = body
        if STATE["fail"] > 0:
            STATE["fail"] -= 1
            self.log(body, {"status": 500})
            return self.reply(500, {"error": {"message": "fake outage"}})
        systems = " ".join(m.get("content") or "" for m in body.get("messages", []) if m["role"] == "system")
        try:
            answer = assistant(body) if re.search(r"dashboard", systems, re.I) else other(body)
        except Exception as e:  # keep the stub alive; the app sees a model error
            self.log(body, {"status": 500, "exception": repr(e)})
            return self.reply(500, {"error": {"message": repr(e)}})
        self.log(body, answer)
        self.reply(200, answer)

    def log(self, request, response):
        with LOCK, open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps({"at": time.time(), "request": request, "response": response}, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    print(f"fake OpenRouter on http://127.0.0.1:{port}/api/v1 (log: {LOG})", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
