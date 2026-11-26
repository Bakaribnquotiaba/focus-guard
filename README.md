# Focus Guard

A personal Android self-control app (made for an Honor X9c, works on Android 10+).

## What it does

| Rule | What happens |
|---|---|
| **Facebook** | Open only in 5-minute slots: 09:30, 11:00, 12:30, 13:30, 15:00, 16:30, 17:30, 18:30, 19:30, 20:30, 21:30, 22:30 (1 hour a day). Outside a slot it closes right away with a warning. The **3rd attempt** in a day triggers the penalty. Needs automatic date, time and time zone; if those are off, Facebook stays closed. |
| **Adult sites** | Blocked in every browser, incognito too (local DNS filter + Cloudflare for Families + address-bar check). Trying one triggers the penalty. |
| **Dating apps / sites** | Opening or installing one triggers the penalty, and the app keeps asking you to uninstall it. |
| **Chat with "blue sea"** | Opening that conversation in Google Chat, Gmail, WhatsApp, Messenger, Telegram and other chat apps: very hard vibration and the app closes. The **3rd attempt** in a day triggers the penalty. |
| **Penalty** | About 5 seconds of maximum vibration, then the phone locks and stays locked for **15 minutes** (every unlock is locked again). Emergency calls still work from the lock screen. To end it early, tap **Solve 5 questions to unlock early** and answer 5 random questions in a row (multiplication, number sequences, remainders, word problems). A wrong answer starts over after a 30-second wait. |
| **Self-protection** | Any settings screen that shows Focus Guard (app info, accessibility, device admin, VPN) closes unless you unlocked protection with your PIN in the app (10 minutes). Device admin blocks uninstalling. |

A real power-off isn't possible on an Honor X9c: Android only lets rooted phones or the system itself shut down, and Honor phones can't be rooted. The 15-minute lock replaces it.

## Banking apps

Banking and payment apps (InstaPay, bank apps, wallets, Fawry, Vodafone Cash) are left out of the web filter automatically, because many refuse to open while a VPN is on. To skip another app, open Focus Guard → **Choose more trusted apps** (needs the PIN).

If a banking app then says it has no internet, turn off *Block connections without VPN* in the VPN settings (keep *Always-on VPN* on): Android blocks skipped apps while that switch is on.

## Install with Obtainium

1. In Obtainium tap **Add app** and paste `https://github.com/Bakaribnquotiaba/focus-guard`.
2. Install. Every push to `main` builds a new release, and Obtainium picks it up.

## Setup on the phone (in this order)

1. Open Focus Guard and create your PIN. You then have 30 minutes to finish setup.
2. **Device admin** → Turn on.
3. **Web filter** → Start, accept the VPN request.
4. **Always-on VPN** → tap the ⚙ next to Focus Guard → turn on *Always-on VPN*. Leave *Block connections without VPN* off, otherwise banking apps that skip the filter lose internet.
5. **Battery** → Allow. On Honor also: Settings → Apps → App launch → Focus Guard → *Manage manually*, all three switches on.
6. **Automatic date & time** → on (and automatic time zone).
7. **Accessibility** → Installed apps → Focus Guard → on. If it's greyed out: Settings → Apps → Focus Guard → ⋮ → *Allow restricted settings*, then try again.
8. Settings → Network → **Private DNS** → *Off*, so DNS goes through the filter.

To update or change anything later, open Focus Guard, tap **Unlock protection for 10 minutes** and enter the PIN first.

## Changing the rules

All rules are in `app/src/main/java/com/mossab/focusguard/Rules.kt` (slots, blocked contact names, chat apps, dating apps, words).

## Signing

The release key is `signing/release.jks` (password `focusguard`, or the `KEYSTORE_PASSWORD` secret if you set one). Keep this file: Android only accepts updates signed with the same key.
