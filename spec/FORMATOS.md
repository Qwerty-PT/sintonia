# Sintonia — formatos (fonte de verdade para APK e desktop)

As regras abaixo valem para todas as versões. Cada regra tem exemplos em `fixtures/` com o resultado
esperado em JSON; os testes de cada versão correm sobre esses ficheiros.

| Exemplo | Testado por |
|---|---|
| `fixtures/m3u/basico.m3u` | APK: `SpecFixturesTest.m3uBasico` · desktop: `npm test` (`desktop/test/spec.test.mjs`) |
| `fixtures/epg/basico.xml` | APK: `SpecFixturesTest.epgBasico` · desktop: `npm test` |
| `fixtures/listas/exemplo.txt` | Configurador: `node spec/check-configurador.mjs` (APK: backlog passo 2) |

Quando uma regra muda: atualizar este ficheiro + o exemplo + o `.expected.json`, depois o código.

## 1. Lista M3U

**Cabeçalho** — primeira linha `#EXTM3U`; guia em `url-tvg="a,b"` (ou `x-tvg-url`), separado por vírgulas,
só endereços `http(s)`.

**Canal** = linha `#EXTINF` + diretivas opcionais + linha do endereço.
- Atributos `chave="valor"` antes da primeira vírgula **fora de aspas**; o nome é o texto depois dela
  (pode ter vírgulas). Sem nome → `tvg-name` → último segmento do URL.
- `group-title`: se tiver `;`, conta só o primeiro; vazio/ausente → `"Outros"`.
- `tvg-logo` vazio → sem logótipo. `tvg-id` → ligação ao guia.
- Rádio: `radio="true"` ou grupo que contém "rádio"/"radio" (sem distinguir maiúsculas).
- Chave estável do canal (favoritos, último visto): `grupo|nome` (o URL pode mudar, ex.: tokens).

**Diretivas** (aplicam-se ao canal em curso; cada `#EXTINF` recomeça do zero):
| Diretiva | Efeito |
|---|---|
| `#EXTVLCOPT:http-user-agent=` | User-Agent (aspas à volta removidas) |
| `#EXTVLCOPT:http-referrer=` / `http-referer=` | cabeçalho `Referer` |
| `#EXTVLCOPT:http-origin=` | cabeçalho `Origin` |
| `#KODIPROP:inputstream.adaptive.license_type=` | tipo de DRM (ex.: `org.w3.clearkey`) |
| `#KODIPROP:inputstream.adaptive.license_key=` | `kid:key[,kid:key]` em hex, JSON, ou URL de licenças |
| `#KODIPROP:inputstream.adaptive.stream_headers=` / `manifest_headers=` | `K=V&K=V` (URL-encoded) |
| `#EXTHTTP:{"K":"V",…}` | cabeçalhos; `User-Agent` vai para o UA |

**Endereço**: `url|K=V&K=V` → cabeçalhos extra (URL-decoded; `User-Agent` vai para o UA).
Só `http://` e `https://` entram; o resto (ex.: `plugin://`) é ignorado. Linhas `#…` que não são
diretivas (incluindo URLs comentados) são ignoradas — um `#EXTINF` sem endereço válido não gera canal.

**DRM ClearKey**: `kid:key` em hex → JSON Web Key (`kty: oct`, `kid`/`k` em base64url sem `=`).

## 2. Guia (XMLTV)

- Aceita `.xml`, `.xml.gz` e `.xml.xz` (detetado pelos primeiros bytes, não pela extensão).
- Datas `AAAAMMDDhhmmss ±hhmm` (sem fuso → UTC).
- Guardar só os canais pedidos: por `tvg-id`, ou pelo `display-name` igual ao nome do canal
  (sem distinguir maiúsculas) → mapa `nome → id`.
- Guardar só programas dentro da janela (`stop > de` e `start < até`); a app usa 2 h antes a 48 h depois.
- Programas ordenados por início; `title` obrigatório; `desc` opcional (cortada a 400 caracteres).
- `stop` em falta ou ≤ `start` → início + 30 min.

## 3. Ficheiro de listas por país

```
###M3U Lists###          ← linhas começadas por # são ignoradas
https://...              ← antes de qualquer [XX]: lista sem país (sem VPN)
[PT]                     ← secção de país (2–3 letras, guardado em maiúsculas)
01                       ← linha que não é URL = nome da lista seguinte
https://...
```
- Secção repetida → junta à existente. Ficheiro VPN = `<código em minúsculas>.conf`.
- URL sem nome antes → nome vazio (a app mostra "Lista N").
- Nome seguido de outro nome (sem URL entre eles) → o primeiro é descartado e a sua linha reportada.
