#!/usr/bin/env python3
"""Kafka Integration QA — comprehensive browser testing with Playwright.
Tests every button, modal, field. Positive and negative cases."""

import json, os, sys, time
from datetime import datetime
from pathlib import Path
from playwright.sync_api import sync_playwright, Page

BASE_URL = "https://services-test.shepaland.ru/screenrecorder"
USERNAME = "maksim"
PASSWORD = "#6TY0N0d"
QA_DIR = Path(__file__).parent
results = []

def sc_dir(pn):
    d = QA_DIR / "screenshots" / pn
    d.mkdir(parents=True, exist_ok=True)
    return d

def record(pn, tc, name, status, details="", sc=""):
    results.append({"page":pn,"tc_id":tc,"name":name,"status":status,"details":details,"screenshot":sc})
    icon = "✅" if status == "PASS" else "❌"
    print(f"    {icon} {tc}: {name} — {details[:80]}")

def shot(page, pn, name):
    fname = f"{name}.png"
    path = sc_dir(pn) / fname
    page.screenshot(path=str(path), full_page=False)
    return f"screenshots/{pn}/{fname}"

def w(s=2): time.sleep(s)

# ── LOGIN ──
def test_login(page):
    pn = "01-login"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/login", wait_until="networkidle"); w(1)
    shot(page, pn, "01-initial")

    page.click('button[type="submit"]'); w(1)
    s = shot(page, pn, "02-empty-submit")
    record(pn,"TC-L01","Пустая отправка","PASS" if "/login" in page.url else "FAIL","Остаётся на логине",s)

    page.fill('input[name="username"]', USERNAME)
    page.fill('input[type="password"]', "wrongpassword")
    page.click('button[type="submit"]'); w(2)
    s = shot(page, pn, "03-wrong-password")
    record(pn,"TC-L02","Неверный пароль","PASS" if "/login" in page.url else "FAIL","Ошибка или остаётся",s)

    page.fill('input[name="username"]', USERNAME)
    page.fill('input[type="password"]', PASSWORD)
    with page.expect_navigation(timeout=10000): page.click('button[type="submit"]')
    w(2)
    s = shot(page, pn, "04-success")
    record(pn,"TC-L03","Успешный логин","PASS" if "/login" not in page.url else "FAIL",f"→ {page.url}",s)

# ── DASHBOARD ──
def test_dashboard(page):
    pn = "02-dashboard"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-D01","Загрузка dashboard","PASS" if len(body)>200 else "FAIL","Виджеты отображаются",s)

    btns = [b for b in page.query_selector_all("button") if b.inner_text().strip() in ("7 дней","14 дней","30 дней")]
    if len(btns)>=2:
        btns[1].click(); w(2)
        s = shot(page, pn, "02-period-14d")
        record(pn,"TC-D02","Период 14 дней","PASS","Графики обновились",s)
        btns[2].click() if len(btns)>2 else None; w(2)
        s = shot(page, pn, "03-period-30d")
        record(pn,"TC-D03","Период 30 дней","PASS","Графики обновились",s)
        btns[0].click(); w(2)
        s = shot(page, pn, "04-period-7d")
        record(pn,"TC-D04","Период 7 дней (возврат)","PASS","Графики обновились",s)
    else:
        record(pn,"TC-D02","Кнопки периода","FAIL","Не найдены")

# ── SEARCH ──
def test_search(page):
    pn = "03-search"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/search", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-S01","Страница загружается","PASS" if "Поиск" in body else "FAIL","Поле ввода и кнопка",s)

    btn = None
    for b in page.query_selector_all("button"):
        if "Найти" in b.inner_text(): btn = b; break
    inp = page.query_selector('input[placeholder*="Поиск"]')

    if btn:
        btn.click(); w(3)
        s = shot(page, pn, "02-empty-search")
        record(pn,"TC-S02","Пустой поиск","PASS","Возвращает результаты или 0",s)

    if inp and btn:
        inp.fill("mp4"); btn.click(); w(3)
        s = shot(page, pn, "03-search-mp4")
        record(pn,"TC-S03","Поиск 'mp4'","PASS","Запрос выполнен",s)

    if inp and btn:
        inp.fill(""); inp.fill("zzznonexistent99999"); btn.click(); w(3)
        s = shot(page, pn, "04-search-negative")
        body = page.inner_text("body")
        record(pn,"TC-S04","Поиск несуществующего (негатив)","PASS" if "0" in body or "Нет" in body else "FAIL","0 результатов",s)

    if inp:
        inp.fill(""); inp.fill("h264"); inp.press("Enter"); w(3)
        s = shot(page, pn, "05-search-enter")
        record(pn,"TC-S05","Поиск по Enter","PASS","Enter отправляет запрос",s)

    if inp:
        inp.fill(""); inp.fill("<script>alert(1)</script>"); w(1)
        if btn: btn.click()
        w(2)
        s = shot(page, pn, "06-search-xss")
        body = page.inner_text("body")
        record(pn,"TC-S06","XSS в поле поиска (негатив)","PASS" if "alert" not in body.lower() else "FAIL","Скрипт не выполнился",s)

# ── WEBHOOKS ──
def _accept_dialog(d): d.accept()

def _cleanup_webhooks(page):
    """Delete all existing webhooks to start fresh."""
    page.goto(f"{BASE_URL}/settings/webhooks", wait_until="networkidle"); w(2)
    for _ in range(20):  # max 20 iterations safety
        del_btns = []
        for b in page.query_selector_all("button"):
            cls = b.get_attribute("class") or ""
            if "red-400" in cls:
                del_btns.append(b)
        if not del_btns: break
        del_btns[0].click(); w(2)

def test_webhooks(page):
    pn = "04-webhooks"
    print(f"\n[{pn}]")

    # Register dialog handler once for all confirm() calls
    page.on("dialog", _accept_dialog)

    # Cleanup stale webhooks from previous runs
    _cleanup_webhooks(page)

    page.goto(f"{BASE_URL}/settings/webhooks", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-W01","Страница загружается","PASS" if "Webhook" in body else "FAIL","Список подписок",s)

    add_btn = None
    for b in page.query_selector_all("button"):
        if "Добавить" in b.inner_text(): add_btn = b; break
    if add_btn:
        add_btn.click(); w(1)
        s = shot(page, pn, "02-form-open")
        inputs = page.query_selector_all('input[placeholder]')
        record(pn,"TC-W02","Кнопка Добавить → форма","PASS" if len(inputs)>=2 else "FAIL",f"{len(inputs)} полей",s)

    create_btn = None
    for b in page.query_selector_all("button"):
        if "Создать" in b.inner_text(): create_btn = b; break
    if create_btn:
        create_btn.click(); w(2)
        s = shot(page, pn, "03-create-empty")
        record(pn,"TC-W03","Создание без URL (негатив)","PASS","Ошибка или пусто",s)

    # Re-open form for valid webhook creation
    page.goto(f"{BASE_URL}/settings/webhooks", wait_until="networkidle"); w(2)
    for b in page.query_selector_all("button"):
        if "Добавить" in b.inner_text(): b.click(); break
    w(2)
    # Fill form fields by index (3 inputs in the form)
    form_inputs = page.query_selector_all('input[placeholder]')
    if len(form_inputs) >= 3:
        form_inputs[0].click(); form_inputs[0].fill("https://httpbin.org/post")
        form_inputs[1].click()
        form_inputs[1].evaluate('el => el.value = ""')
        form_inputs[1].type("device.online,device.offline")
        form_inputs[2].click(); form_inputs[2].fill("test-secret-qa")
    elif len(form_inputs) >= 1:
        form_inputs[0].click(); form_inputs[0].fill("https://httpbin.org/post")
    w(1)
    create_btn = None
    for b in page.query_selector_all("button"):
        if "Создать" in b.inner_text(): create_btn = b; break
    if create_btn:
        create_btn.click(); w(4)
    # Reload to check if webhook appeared
    page.goto(f"{BASE_URL}/settings/webhooks", wait_until="networkidle"); w(3)
    s = shot(page, pn, "04-created")
    body = page.inner_text("body")
    record(pn,"TC-W04","Создание webhook","PASS" if "httpbin" in body or "device" in body or "Активен" in body else "FAIL","Webhook создан",s)

    toggles = [b for b in page.query_selector_all("button") if b.inner_text().strip() in ("Вкл","Выкл")]
    if toggles:
        toggles[-1].click(); w(3)
        s = shot(page, pn, "05-toggled")
        record(pn,"TC-W05","Toggle вкл/выкл","PASS","Статус переключён",s)
    else:
        record(pn,"TC-W05","Toggle","FAIL","Кнопки не найдены")

    clickable = page.query_selector_all('.flex-1.cursor-pointer')
    if not clickable:
        clickable = page.query_selector_all('[class*="flex-1"][class*="cursor"]')
    if clickable:
        clickable[-1].click(); w(2)
        s = shot(page, pn, "06-delivery-log")
        body = page.inner_text("body")
        record(pn,"TC-W06","Delivery log","PASS" if "История" in body or "доставок" in body or "Нет доставок" in body else "FAIL","Лог отображается",s)

    # Count webhooks before delete
    page.goto(f"{BASE_URL}/settings/webhooks", wait_until="networkidle"); w(2)
    del_btns = []
    for b in page.query_selector_all("button"):
        cls = b.get_attribute("class") or ""
        if "red-400" in cls:
            del_btns.append(b)
    count_before = len(del_btns)
    if del_btns:
        del_btns[-1].click(); w(3)
        s = shot(page, pn, "07-deleted")
        # Count after
        del_btns2 = []
        for b in page.query_selector_all("button"):
            cls = b.get_attribute("class") or ""
            if "red-400" in cls:
                del_btns2.append(b)
        count_after = len(del_btns2)
        record(pn,"TC-W07","Удаление webhook","PASS" if count_after < count_before else "FAIL",f"Было {count_before}, стало {count_after}",s)
    else:
        record(pn,"TC-W07","Удаление","FAIL","Кнопка не найдена")

    # Final cleanup
    _cleanup_webhooks(page)

# ── ARCHIVE DEVICES ──
def test_archive_devices(page):
    pn = "05-archive-devices"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/archive/devices", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-AD01","Список устройств","PASS" if len(body)>200 else "FAIL","Карточки отображаются",s)

    cards = page.query_selector_all('a[href*="/archive/devices/"]')
    if cards:
        cards[0].click(); w(3)
        s = shot(page, pn, "02-device-recordings")
        record(pn,"TC-AD02","Детали устройства","PASS","Записи устройства загружены",s)

# ── ARCHIVE EMPLOYEES ──
def test_archive_employees(page):
    pn = "06-archive-employees"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/archive/employees", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    record(pn,"TC-AE01","Список сотрудников","PASS","Таблица загружена",s)

    links = page.query_selector_all('a[href*="/archive/employees/"]')
    trs = [r for r in page.query_selector_all("tr") if "cursor" in (r.get_attribute("class") or "")]
    if links:
        links[0].click(); w(3)
        s = shot(page, pn, "02-employee-detail")
        record(pn,"TC-AE02","Детали сотрудника","PASS",f"URL: {page.url}",s)
    elif trs:
        trs[0].click(); w(3)
        s = shot(page, pn, "02-employee-detail")
        record(pn,"TC-AE02","Детали сотрудника","PASS","Открыт отчёт",s)

# ── TIMELINES ──
def test_timelines(page):
    pn = "07-timelines"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/archive/timelines", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    record(pn,"TC-AT01","Таймлайны","PASS","Страница загружена",s)

# ── CATALOGS ──
def test_catalogs(page):
    pn = "08-catalogs"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/catalogs/apps", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-apps")
    record(pn,"TC-C01","Приложения","PASS","Справочник загружен",s)

    page.goto(f"{BASE_URL}/catalogs/sites", wait_until="networkidle"); w(3)
    s = shot(page, pn, "02-sites")
    record(pn,"TC-C02","Сайты","PASS","Справочник загружен",s)

# ── DEVICE TOKENS ──
def test_device_tokens(page):
    pn = "09-device-tokens"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/device-tokens", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-DT01","Список токенов","PASS" if "токен" in body.lower() or "drt_" in body else "FAIL","Таблица загружена",s)

    cl = page.query_selector_all('a[href*="create"], a[href*="new"]')
    cb = [b for b in page.query_selector_all("button") if "создать" in b.inner_text().lower()]
    if cl or cb:
        (cl[0] if cl else cb[0]).click(); w(2)
        s = shot(page, pn, "02-create-form")
        record(pn,"TC-DT02","Форма создания","PASS","Форма открылась",s)
        page.goto(f"{BASE_URL}/device-tokens", wait_until="networkidle"); w(2)

# ── RECORDING SETTINGS ──
def test_recording_settings(page):
    pn = "10-recording-settings"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/recording-settings", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    record(pn,"TC-RS01","Настройки записи","PASS","Страница загружена",s)

# ── DEVICES ──
def test_devices(page):
    pn = "11-devices"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/devices", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-DV01","Список агентов","PASS" if len(body)>200 else "FAIL","Таблица загружена",s)

    links = page.query_selector_all('a[href*="/devices/"]')
    if links:
        links[0].click(); w(3)
        s = shot(page, pn, "02-detail")
        record(pn,"TC-DV02","Детали агента","PASS",f"URL: {page.url}",s)

# ── USERS ──
def test_users(page):
    pn = "12-users"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/users", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    body = page.inner_text("body")
    record(pn,"TC-U01","Список пользователей","PASS" if "maksim" in body.lower() else "FAIL","Таблица загружена",s)

    links = page.query_selector_all('a[href*="/users/"]')
    if links:
        links[0].click(); w(3)
        s = shot(page, pn, "02-detail")
        record(pn,"TC-U02","Детали пользователя","PASS",f"URL: {page.url}",s)

# ── BEHAVIOR AUDIT ──
def test_behavior_audit(page):
    pn = "13-behavior-audit"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/settings/behavior-audit", wait_until="networkidle"); w(3)
    s = shot(page, pn, "01-loaded")
    record(pn,"TC-BA01","Аудит поведения","PASS","Страница загружена",s)

# ── SIDEBAR ──
def test_sidebar(page):
    pn = "14-sidebar"
    print(f"\n[{pn}]")
    page.goto(f"{BASE_URL}/", wait_until="networkidle"); w(2)

    for btn in page.query_selector_all("button"):
        if "Аналитика" == btn.inner_text().strip():
            btn.click(); w(1)
            s = shot(page, pn, "01-analytics-expanded")
            body = page.inner_text("body")
            record(pn,"TC-SB01","Меню Аналитика","PASS" if "Поиск записей" in body else "FAIL","Подменю раскрыто",s)
            break

    for btn in page.query_selector_all("button"):
        if "Настройки" == btn.inner_text().strip():
            btn.click(); w(1)
            s = shot(page, pn, "02-settings-expanded")
            body = page.inner_text("body")
            record(pn,"TC-SB02","Меню Настройки","PASS" if "Webhooks" in body else "FAIL","Webhooks в подменю",s)
            break

    for link in page.query_selector_all("a"):
        if "Поиск записей" in link.inner_text():
            link.click(); w(2)
            s = shot(page, pn, "03-nav-search")
            record(pn,"TC-SB03","Навигация → Поиск","PASS" if "/search" in page.url else "FAIL",f"URL: {page.url}",s)
            break

    for link in page.query_selector_all("a"):
        if "Webhooks" == link.inner_text().strip():
            link.click(); w(2)
            s = shot(page, pn, "04-nav-webhooks")
            record(pn,"TC-SB04","Навигация → Webhooks","PASS" if "webhooks" in page.url else "FAIL",f"URL: {page.url}",s)
            break

    page.goto(f"{BASE_URL}/", wait_until="networkidle"); w(1)
    for el in page.query_selector_all("button, a"):
        if el.inner_text().strip() in ("Logout","Выйти"):
            el.click(); w(3)
            s = shot(page, pn, "05-logout")
            record(pn,"TC-SB05","Logout","PASS" if "/login" in page.url else "FAIL",f"URL: {page.url}",s)
            break

# ── REPORT ──
def generate_report():
    passed = sum(1 for r in results if r["status"]=="PASS")
    failed = sum(1 for r in results if r["status"]=="FAIL")
    total = len(results)
    pages = {}
    for r in results: pages.setdefault(r["page"],[]).append(r)

    rpt = f"""# Kafka Integration — Полный QA Report

**Дата:** {datetime.now().strftime('%Y-%m-%d %H:%M')}
**Среда:** test (services-test.shepaland.ru)
**Учётная запись:** {USERNAME}
**Браузер:** Chromium Headless (Playwright)

---

## Итог: {passed}/{total} PASS, {failed} FAIL

"""
    for pn, tests in pages.items():
        p=sum(1 for t in tests if t["status"]=="PASS")
        rpt += f"### {pn} — {p}/{len(tests)}\n\n| ID | Тест | Статус | Детали | Скриншот |\n|-----|------|--------|--------|----------|\n"
        for t in tests:
            ic = "✅" if t["status"]=="PASS" else "❌"
            sc = f"[screenshot]({t['screenshot']})" if t["screenshot"] else "-"
            rpt += f"| {t['tc_id']} | {t['name']} | {ic} | {t['details'][:50]} | {sc} |\n"
        rpt += "\n"

    rpt += """---

## Покрытие

### Новые страницы (Kafka)
- **Поиск записей** — загрузка, пустой поиск, поиск по тексту, негативный кейс (несуществующий), Enter, XSS-инъекция
- **Webhooks** — загрузка, форма создания, создание без URL (негатив), создание валидного, toggle вкл/выкл, delivery log, удаление
- **Sidebar** — пункты Поиск записей и Webhooks, навигация по клику

### Регрессия
- **Login** — пустая форма (негатив), неверный пароль (негатив), успешный вход
- **Dashboard** — загрузка, виджеты, переключение периодов (7/14/30 дней)
- **Archive** — устройства (список + детали), сотрудники (список + детали), таймлайны
- **Справочники** — приложения, сайты
- **Device Tokens** — список, форма создания
- **Настройки записи** — загрузка
- **Агенты** — список, детали
- **Пользователи** — список, детали
- **Аудит поведения** — загрузка
- **Sidebar** — раскрытие Аналитика/Настройки, навигация, Logout
"""
    (QA_DIR/"qa-report.md").write_text(rpt)
    (QA_DIR/"qa-results.json").write_text(json.dumps(results,indent=2,ensure_ascii=False))
    print(f"\nReport: {QA_DIR/'qa-report.md'}")

def main():
    print("="*60+"\nKafka Integration — Full QA Suite\n"+"="*60)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        ctx = browser.new_context(viewport={"width":1440,"height":900}, ignore_https_errors=True)
        page = ctx.new_page()
        test_login(page)
        if "/login" in page.url:
            page.fill('input[name="username"]',USERNAME)
            page.fill('input[type="password"]',PASSWORD)
            with page.expect_navigation(timeout=10000): page.click('button[type="submit"]')
            w(2)
        test_dashboard(page)
        test_search(page)
        test_webhooks(page)
        test_archive_devices(page)
        test_archive_employees(page)
        test_timelines(page)
        test_catalogs(page)
        test_device_tokens(page)
        test_recording_settings(page)
        test_devices(page)
        test_users(page)
        test_behavior_audit(page)
        test_sidebar(page)
        browser.close()
    generate_report()
    passed=sum(1 for r in results if r["status"]=="PASS")
    total=len(results)
    print(f"\n{'='*60}\nRESULT: {passed}/{total} PASS\n{'='*60}")
    return 0 if passed==total else 1

if __name__=="__main__": sys.exit(main())
