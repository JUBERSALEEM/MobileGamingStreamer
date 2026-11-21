# MGS design tokens

Single claymorphism design language for Mobile Gaming Streamer. Every
product color in the Compose UI lives in `MgsPalette` (`AppTheme.kt`) and is
read through `LocalMgsPalette` — no screen or component may scatter its own
brand hex values. The table below is the contract; the palette object is the
implementation.

## Palette

| Token group | Token | Hex | Role |
|---|---|---|---|
| Surface | `background` | `#E2E4EE` | App canvas |
| Surface | `backgroundDeep` | `#D2D5E4` | Canvas gradient foot |
| Surface | `surface` | `#F4F5FA` | Card mid |
| Surface | `surfaceDeep` | `#DFE2EC` | Card foot |
| Surface | `elevated` | `#FCFCFE` | Card crown |
| Surface | `inset` | `#D7DBE8` | Recessed wells |
| Surface | `highlight` | `#FFFFFF` | Top-left light |
| Text | `text` | `#23232E` | Graphite body |
| Text | `muted` | `#5D5A73` | Captions, idle values |
| Text | `disabled` | `#A9A5BE` | Disabled affordances |
| Primary | `primary` | `#5B3DF5` | Electric-violet filled actions |
| Primary | `primaryDeep` | `#3D2AB8` | Filled gradient foot, rims |
| Primary | `primaryInk` | `#4330B8` | Text/icons on light violet |
| Primary | `primarySoft` | `#E2DBFD` | Tinted fills, badges |
| Primary | `onPrimary` | `#FFFFFF` | Text on filled actions |
| Secondary | `secondary` | `#D95F45` | Restrained coral, secondary actions |
| Secondary | `secondaryDeep` | `#B84A35` | Coral gradient foot |
| Secondary | `secondaryInk` | `#A03A29` | Text on light coral |
| Accent | `accent` | `#0097A7` | Teal: live, audio and technical states |
| Accent | `accentDeep` | `#0B7280` | Live ink on light surfaces |
| Accent | `accentSoft` | `#D5EFF3` | Live/tech badge fills |
| Success | `success` / `successDeep` / `successSoft` | `#2E9E63` / `#1F7350` / `#D8EFDF` | Healthy, ready, system-ok |
| Warning | `warning` / `warningDeep` / `warningSoft` | `#BE7A12` / `#8A570A` / `#F6EACD` | Degraded, action needed, not an error |
| Danger | `danger` / `dangerDeep` / `dangerSoft` | `#D1344C` / `#A11F36` / `#F9DCE1` | Errors and destructive actions only |
| Clay | `edge` | `#FFFFFF` | Sculpted rim crown |
| Clay | `clayShadow` | `#99A0BB` | Cool ambient shadow |
| Clay | `clayEdge` | `#C2C8DC` | Rim shadow side |

## Usage rules

- One primary action per card: the filled electric-violet block. Everything
  else steps down to raised slabs, then recessed wells.
- Red is reserved for errors and destructive ends (stop live, mute states,
  failures). The happy-path live indicator is teal, never red.
- Standby is graphite on clay, never a lit-up color — an idle pill must not
  read as a false positive.
- Technical readouts (URLs, keys, bitrates, sources, diagnostics) use the
  monospace face; live technical values are teal.
- Section icons sit in tinted `ClayIconBadge` slabs, never as bare glyphs on
  card gradients.
- Depth presets: `Deep` for hero cards and dialogs, `Medium` for content
  cards, `Subtle` for rows, badges and the navigation dock items.
- Minimum touch target is 48dp on every button, nav destination, option tile
  and overlay control.

## Status tones (`ClayTone`)

`Live` teal, `Active` green, `Idle` graphite, `Accent` violet,
`Warning` amber, `Alert` red. The pill well stays neutral clay; the tone
shows in the dot and label ink.

## Overlay dark glass (View system)

The floating panel cannot read Compose tokens, so it mirrors them with fixed
fills. Lightened text tints hold contrast on the dark graphite surface:

| Element | Fill | Text |
|---|---|---|
| Panel | `#262633 → #1B1B25` | — |
| Default slab | `#353542` | `#E9E7F5` |
| Primary / speaker on | `#5B3DF5` | `#FFFFFF` |
| Secondary / chat | `#D95F45` | `#FFFFFF` |
| Teal / stream audio on | `#0097A7` | `#FFFFFF` |
| Danger / stop, muted, errors | `#D1344C` | `#FFFFFF` |
| Status: live / connecting / error / setup | — | `#6FD8E3` / `#F2C879` / `#FF8A7A` / `#B9B4CC` |
| Section labels, footer | — | `#9C97B3` / `#8E89A3` |
| Chat field well | `#1B1B25` | `#E9E7F5`, hint `#8E89A3` |

## Accessibility notes

- Interactive clay controls expose `Role.Button`, `Role.Tab`,
  `Role.RadioButton` or `Role.Switch` with labels; decorative icons carry no
  description so TalkBack never double-reads a visible label.
- Page titles are headings; the whole toggle row is one switch control.
- `MainActivity` announces broadcast transitions (`You are live`,
  `Connecting to stream`, `Broadcast error…`, `Broadcast stopped`) exactly
  once per state change — bitrate ticks never spam the stream.
- Overlay buttons carry content descriptions; status, IDs and the footer use
  the monospace face.
- Body text on clay holds at least 4.5:1 (`text` on `surface`,
  `primaryInk` on `primarySoft`, `accentDeep` on `accentSoft`); large display
  type holds 3:1. Verify on a physical device before release claims.
