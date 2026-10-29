# pawsocks

плагин с уникальными фишками сервера [pawsocks](https://femden.zeroiq.club). paper 1.21.11, java 21.
для цены при наведении нужен плагин [packetevents](https://modrinth.com/plugin/packetevents) — без него всё остальное работает.

## фишки

| фишка | как пользоваться |
|---|---|
| **носки** | крафт из шерсти по форме ботинок: верхний ряд — цвет, нижний — полоска. в носках на 40% меньше урона от падения и быстрее копится уют |
| **уют** | шкала 0–100%: растёт у зажжённого костра, рядом со своим питомцем, когда сидишь или спишь, в носках. на 100% — лёгкая регенерация, пока тебя не бьют |
| **погладить** | шифт + пкм пустой рукой по кошке, собаке, лисе, кролику или попугаю: сердечки, звук, +уют |
| **обнимашки** | `/hug ник` → второй игрок жмёт [обнять] в чате → сердечки, мурчание, регенерация и уют обоим |
| **следы лапок** | за игроком остаются отпечатки лапок из частиц. цвет — в `/paw`. 4 цвета у всех, ещё 7 (радуга, трансфлаг, фембой-флаг…) — с правом `pawsocks.trails.premium` |
| **переноска** | крафт: нить-кожа-нить / кожа-пусто-кожа / кожа ×3. пкм по своему питомцу — посадить внутрь, пкм по блоку — выпустить |
| **лапки 🐾** | своя валюта, ≈ доллар. `/bal`, `/pay ник сумма`, `/baltop`. счета в `data.db` (sqlite), все операции в журнале |
| **продажа серверу** | `/sell` (меню), `/sell hand`, `/sell all`. цены в `prices.yml`; чем больше вещи продали, тем дешевле она (насыщение рынка, остывает за 12 ч) |
| **цена при наведении** | в описании предмета видно, сколько даст сервер. только для глаз игрока, предметы не меняются; в творческом режиме выключено |
| **аукцион** | `/ah`, `/ah sell цена`. налог 5%, лот 48 ч, мои лоты, возврат непроданного, уведомление продавцу |
| **дома** | `/sethome [имя]`, `/home [имя]`, `/delhome`, `/homes`. лимит по правам, телепорт через 3 с |
| **меню `/paw`** | профиль (уют, обнимашки, время на сервере), выбор следов, подсказки, настройки |

## команды и права

| команда | право | по умолчанию |
|---|---|---|
| `/paw` (`/lapka`) | `pawsocks.menu` | все |
| `/hug ник`, `/hug accept`, `/hug deny` | `pawsocks.hug` | все |
| — следы лапок | `pawsocks.trails` | все |
| — особые цвета следов | `pawsocks.trails.premium` | оп |
| `/pawsocks reload` | `pawsocks.admin` | оп |
| `/bal [ник]`, `/baltop` | `pawsocks.balance` | все |
| `/pay ник сумма` | `pawsocks.pay` | все |
| `/eco give\|take\|set ник сумма`, `/eco info` | `pawsocks.eco.admin` | оп |
| `/sell`, `/sell hand`, `/sell all` | `pawsocks.sell` | все |
| `/ah`, `/ah sell цена` | `pawsocks.ah` | все |
| — лотов на аукционе больше 5 | `pawsocks.ah.listings.<n>` | — |
| `/sethome`, `/home`, `/delhome`, `/homes` | `pawsocks.homes` | все |
| — точек дома (по умолчанию 1) | `pawsocks.homes.<n>` | — |
| — без ограничений | `pawsocks.homes.unlimited` | оп |
| — телепорт без задержки | `pawsocks.homes.instant` | никому |

на сервере pawsocks выдано:

```
lp group active permission set pawsocks.homes.2 true
lp group supporter permission set pawsocks.homes.3 true
lp group supporter permission set pawsocks.trails.premium true
lp group moderator permission set pawsocks.homes.4 true
lp group owner permission set pawsocks.homes.unlimited true
```

## настройка

- `plugins/pawsocks/config.yml` — числа: сколько уюта даёт костёр, радиусы, кулдауны, множитель урона от падения. любую фишку можно выключить (`enabled: false`).
- `plugins/pawsocks/prices.yml` — цены скупки.
- `plugins/pawsocks/messages.yml` — все тексты, формат [minimessage](https://docs.advntr.dev/minimessage/format).
- после правки: `/pawsocks reload`.

всё, что плагин помнит об игроках (уют, обнимашки, выбранные следы, настройки), хранится в данных игрока — отдельной базы нет.

## сборка

github actions собирает плагин при каждом пуше в `main` и выкладывает jar в [releases](../../releases). локально: `gradle build`, jar — в `build/libs/`.
