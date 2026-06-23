# Buffer di riproduzione — riferimento

Come vengono calcolati i livelli di buffer (LOW / MID / HIGH / VHIGH / DYNAMIC) e
le tabelle di conversione MB ↔ secondi. Valido per **entrambi** i motori.

Sorgente dei valori: [`BufferTuning.kt`](../app/src/main/java/net/fatshotty/tgstream/util/BufferTuning.kt)
→ `exoBytes()` e `vlcCachingMs()`. Scelta utente in Impostazioni
([`SettingsActivity.kt`](../app/src/main/java/net/fatshotty/tgstream/ui/SettingsActivity.kt)),
default **HIGH** (riproduce il comportamento fluido di v0.1.20 sui TV box tipici).

## Principio

I due motori fissano il buffer su grandezze diverse:

- **ExoPlayer** → fissa i **MB** (il buffer vive sull'heap Java, va limitato in byte).
  I secondi dipendono dal bitrate del file.
- **libVLC** → fissa i **secondi** (`network-caching`, memoria nativa; è anche la
  latenza all'avvio e l'attesa di refill dopo un seek). I MB dipendono dal bitrate.

Conversione:

```
secondi = MB × 8 / Mbps
MB      = secondi × Mbps / 8
```

## I valori sono FISSI (non dipendono dal dispositivo)

Il tier è una scelta esplicita dell'utente; i MB (Exo) e i secondi (VLC) sono
costanti identiche su ogni device. La vecchia logica `autoTier()` basata sulla RAM
**non è più usata** (codice morto, tenuto solo per riferimento).

> ⚠️ Eccezione pratica: **VHIGH (384 MB)** richiede `largeHeap` + heap ~512 MB.
> Su hardware con poca RAM può causare `OutOfMemoryError`: il valore non viene
> ridotto in automatico, va evitato a mano sui device deboli.

## ExoPlayer — MB fissi, secondi calcolati

Tetto in byte per tier. Esiste anche un tetto di tempo separato (max buffer = 300s)
che vincola solo sui bitrate bassi.

| Tier  | MB (fisso) | @20 Mb/s | @50 Mb/s | @80 Mb/s | @120 Mb/s |
|-------|-----------:|---------:|---------:|---------:|----------:|
| LOW   |  48 MB | 19,2 s | 7,7 s  | 4,8 s  | 3,2 s  |
| MID   | 128 MB | 51,2 s | 20,5 s | 12,8 s | 8,5 s  |
| HIGH  | 256 MB | 102 s  | 41 s   | 25,6 s | 17,1 s |
| VHIGH | 384 MB | 154 s* | 61 s   | 38,4 s | 25,6 s |

\* a bitrate bassi può intervenire prima il tetto di tempo (300s).

## libVLC — secondi fissi, MB calcolati

`network-caching` per tier (= anche latenza all'avvio e attesa dopo seek).

| Tier  | Secondi (fisso) | @20 Mb/s | @50 Mb/s | @80 Mb/s | @120 Mb/s |
|-------|----------------:|---------:|---------:|---------:|----------:|
| LOW   |  3 s  | 7,5 MB  | 18,75 MB | 30 MB  | 45 MB  |
| MID   |  6 s  | 15 MB   | 37,5 MB  | 60 MB  | 90 MB  |
| HIGH  | 10 s  | 25 MB   | 62,5 MB  | 100 MB | 150 MB |
| VHIGH | 15 s  | 37,5 MB | 93,75 MB | 150 MB | 225 MB |

## DYNAMIC (solo ExoPlayer) — rampa automatica nel tempo

Il cap in byte cresce in base ai secondi di riproduzione trascorsi, con anti-stallo
che tiene comunque ≥15 s bufferizzati. Implementazione:
[`DynamicLoadControl.kt`](../app/src/main/java/net/fatshotty/tgstream/player/DynamicLoadControl.kt).

| Tempo di play | Tier  | MB     | @80 Mb/s |
|---------------|-------|-------:|---------:|
| 0–10 s        | LOW   |  48 MB | ~4,8 s  |
| 10–20 s       | HIGH  | 256 MB | ~25,6 s |
| 20 s +        | VHIGH | 384 MB | ~38,4 s |

Su **libVLC** il "dynamic" non rampa (il cache è fisso all'init e non
ridimensionabile a runtime): ripiega sul tier fisso **MID** (6 s).

## Note collegate

- **Ripartenza ExoPlayer:** dopo un rebuffer riprende con ~1,5 s di buffer (scelta
  voluta, per ripartenza rapida). Il buffer ampio lo si ottiene scegliendo il tier
  alto o DYNAMIC.
- **Prefetch del proxy:** indipendente dal buffer del player, il proxy Go mantiene
  ~64 MiB di video "caldo" pre-scaricato per assorbire le pause/riprese di ExoPlayer
  (vedi `readAhead` in [`stream.go`](../tgengine/stream.go)).

---
_Ultimo aggiornamento: 2026-06-23._
