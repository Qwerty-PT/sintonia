# Sintonia — leitor M3U simples (Google TV, Fire TV e telemóvel)

Um só APK (`pt.nuno.sintonia`) para Android TV / Google TV, Fire TV e telemóveis Android.
Vem pré-configurado com a lista **M3UPT** (LITUATUI, licença MIT):
`https://raw.githubusercontent.com/LITUATUI/M3UPT/main/M3U/M3UPT.m3u`

## Funcionalidades
- Grupos à esquerda e canais à direita (TV/paisagem); grupos em fita no topo (telemóvel em retrato)
- Logótipos dos canais, ★ Favoritos (manter OK premido / toque longo)
- Grupos visíveis à escolha (Definições → Grupos visíveis…, ou manter OK premido num grupo para o esconder);
  grupos novos da lista aparecem por omissão
- Abre no último canal visto (desligável nas Definições)
- Leitor Media3/ExoPlayer: HLS, DASH, MP4/TS, áudio (rádios com logótipo)
- Cabeçalhos por canal da M3UPT: `#EXTVLCOPT` (User-Agent, Referer, Origin), `#KODIPROP` (ClearKey,
  stream_headers), `#EXTHTTP` e `url|User-Agent=…&Referer=…`
- DRM ClearKey local (`license_key=kid:key`) — necessário para os canais/episódios SIC/Opto da lista
- Religa sozinho até 3 vezes se o stream cair; volta ao direto se ficar para trás
- Lista guardada em cache (abre sem rede) e atualizada ao abrir / a cada 2 h (URLs da TVI expiram)
- **Guia (EPG):** usa o `url-tvg` da lista (M3UPT: ~95 canais com programação), .xml/.gz/.xz;
  transferido em segundo plano, só os canais da lista, janela de 48 h, cache e atualização a cada 12 h.
  Aparece na lista ("▶ programa atual" + painel à direita com o guia do canal focado) e no leitor
  só ao carregar OK (agora, horário + progresso, descrição, a seguir).
- **Lista de canais sobre o vídeo:** ◀ ou botão "Canais" no menu do OK — o vídeo continua a tocar
  numa janela, à direita, com o guia do canal focado por baixo. OK num canal mostra-o na janela
  (pré-visualização); OK outra vez no mesmo canal, ▶ + OK na janela, ou Voltar → ecrã inteiro.
  Opção nas Definições: pré-visualização automática ao percorrer a lista (após 0,7 s).
  Estado no topo: "guia a carregar… / guia: N canais"
- Ao mudar de canal só aparece o nome e o número, pequeno, durante 3 s
- Telemóvel: deslizar da margem esquerda abre a lista; deslizar ▲▼ ou botões para mudar de canal, Picture-in-Picture (Android 8+)

## Comando (TV)
| Tecla | Ação |
|---|---|
| ▲ / CH+ | Canal seguinte |
| ▼ / CH− | Canal anterior |
| OK / Selecionar | Abre os controlos + guia do canal (botão "Canais" na barra de baixo) |
| ◀ | Lista de canais com o vídeo em janela (OK = pré-visualizar, 2.º OK = ecrã inteiro) |
| ▶ na lista → OK | Janela do vídeo → ecrã inteiro |
| ▶ / INFO / GUIA | Mostra o nome do canal (3 s) |
| Voltar (ecrã inteiro) | Abre a lista com o canal na janela |
| Voltar (lista, nos canais/janela) | Volta a ecrã inteiro |
| Voltar (lista, nos grupos) | Sai para o ecrã principal (no telemóvel: Voltar na lista sai) |
| ◀ na lista de canais | Volta aos grupos |

## Definições (⚙)
- Endereço da lista M3U (qualquer URL http/https)
- "Repor M3UPT" volta à predefinição
- Endereço do guia (vazio = o indicado na lista)
- Grupos visíveis… (desmarcar os que não interessam, ex.: "Teste")
- Arranque no último canal
- Pré-visualização automática na lista de canais (desligada = só com OK)

## Instalar
- **Google TV / Android TV / Fire TV:** app *Downloader* (código ou URL do APK) ou `adb install -r Sintonia-1.8.apk`.
  Na Fire TV: Definições → A minha Fire TV → Opções de programador → Instalar apps desconhecidas.
- **Telemóvel:** abrir o APK e permitir "instalar apps desconhecidas".

## Compilar
- Android Studio: abrir a pasta, Build → Build APK(s).
- Linha de comandos: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- GitHub Actions: `.github/workflows/build.yml` compila a cada push na `main` (artefacto `sintonia-apk`).
- Testes: `./gradlew testReleaseUnitTest` (inclui os exemplos de `spec/`) e `node spec/check-configurador.mjs`
  (com a lista real: `M3U_FILE=/caminho/M3UPT.m3u ./gradlew testReleaseUnitTest -i`).

A chave de assinatura está em `keystore/sintonia.jks` (palavra-passe em `app/build.gradle.kts`) para que
todos os builds tenham a mesma assinatura e as atualizações instalem por cima. **Manter o repositório privado.**

## Estrutura
```
app/src/main/java/pt/nuno/sintonia/
  M3uParser.kt      leitor da lista (Kotlin puro, testável)
  EpgParser.kt      leitor XMLTV em streaming (testável) · EpgRepo.kt: transferência + cache do guia
  Http.kt           GET com redireções, descompressão gz/xz
  PlaylistRepo.kt   download + cache + sessão do leitor
  StreamFactory.kt  cabeçalhos HTTP, HLS/DASH, ClearKey
  MainActivity.kt   lista (grupos/canais/favoritos/definições)
  PlayerActivity.kt leitor (zapping, religar, PiP, gestos)
  GuidePanel.kt     painel do guia do canal focado · Catalog.kt: grupos/favoritos
app/src/test/…/SpecFixturesTest.kt  testes sobre os exemplos partilhados de spec/
  Adapters.kt, Prefs.kt, Device.kt
res/layout/exo_player_control_view.xml  menu do OK do Media3 + botão "Canais"
```

## Limitações conhecidas
- Canais com Widevine sem URL de licença não abrem (a M3UPT não usa).
- Alguns canais só funcionam com IP português (nota da própria M3UPT).
- Guia só em linha "agora / a seguir" (sem grelha completa).
- Num Fire TV Stick mais fraco, o primeiro carregamento do guia pode demorar ~1 min (depois fica em cache).

## Versões APK e desktop

O **APK é a versão principal**; o desktop (Mac/Windows/Linux, Electron) segue atrás sem o travar.

```
app/          APK (principal)
desktop/      Electron — Mac/Windows/Linux (porta 1.8 feita)
spec/         formatos (FORMATOS.md) + exemplos com resultado esperado, partilhados pelos testes
ferramentas/  configurador de listas, etc.
```
Regras:
- **Tudo nasce no APK.** Só passa ao desktop depois de estável no APK.
- **Portas em lote**: quando o APK chega a uma versão estável, o desktop recebe tudo de uma vez e fica com
  o mesmo número de versão (desktop 1.8 = mesmo comportamento que APK 1.8).
- **`spec/` é a fonte de verdade**: mudar uma regra = atualizar `spec/FORMATOS.md` + exemplo + `.expected.json`;
  os testes de cada versão correm sobre os mesmos ficheiros.
- No backlog, cada item marcado 📺 APK, 💻 desktop ou ambos.
- Âmbito do desktop: leitor, lista com janela do vídeo, guia, favoritos, grupos visíveis; teclado
  (setas, Enter, Esc) e rato. Fica de fora o que é só de TV/telemóvel.

## Desktop (Mac / Windows / Linux) — pasta `desktop/`

Electron, mesmo comportamento que o APK 1.8 (versão `1.8.0`). O processo principal põe nos pedidos de vídeo
os cabeçalhos de cada canal (User-Agent, Referer, Origin) e desliga o CORS só para esses pedidos;
HLS com hls.js, DASH + ClearKey com Shaka Player; guia .gz/.xz lido em streaming fora do ecrã.

| Tecla / rato | Ação |
|---|---|
| ↑ / PgUp / + · ↓ / PgDn / − | Canal seguinte · anterior |
| Enter / Espaço · mexer o rato | Controlos + guia do canal |
| ← (ou Esc / Backspace em ecrã inteiro) | Lista de canais com o vídeo em janela |
| Na lista: Enter | Pré-visualizar; 2.º Enter = ecrã inteiro (→ + Enter na janela, ou Esc, também) |
| Esc nos grupos da lista | Volta ao ecrã principal |
| → / I | Nome do canal (3 s) |
| F / F11 / duplo clique | Ecrã inteiro da janela |
| M · roda do rato | Som ligado/desligado · volume |
| Botão direito / F num canal | Favorito · botão direito / H num grupo: esconder |
| R (ecrã principal) | Atualizar lista |

Desenvolver: `cd desktop && npm install && npm start` · testes: `npm test` (os mesmos exemplos de `spec/`).
Diagnóstico: `SINTONIA_DEBUG=1 npm start` mostra a consola do ecrã.

Instaladores:
- Linux: `npm run dist:linux` (AppImage + .deb) — compila em qualquer Linux.
- Mac: `npm run dist:mac` num Mac (o `.zip` também compila em Linux, sem assinatura).
- Windows: `npm run dist:win` em Windows (ou Linux com Wine).
- GitHub Actions (`.github/workflows/desktop.yml`): testes a cada alteração; os três instaladores com uma
  tag `desktop-v1.8.0` ou "Run workflow".

Sem certificados de programador (Apple/Microsoft):
- **Mac**: depois de copiar para Aplicações, se disser "danificada" ou "programador não identificado":
  `xattr -cr /Applications/Sintonia.app` (e, em Apple Silicon, se ainda não abrir:
  `codesign --force --deep --sign - /Applications/Sintonia.app`). Depois abre normalmente.
- **Windows**: o SmartScreen avisa na primeira vez → "Mais informações" → "Executar mesmo assim".

## Backlog



### 1. "Receber do telemóvel" (base para o resto) — 📺 APK (desktop: não precisa, tem ficheiros)
- Nas Definições: "Receber do telemóvel" mostra um endereço (ex.: `http://192.168.1.50:8080`).
- Abre-se no browser do telemóvel → página simples para:
  - enviar ficheiros de VPN (`.conf` WireGuard / `.ovpn`) e escolher a região;
  - colar/adicionar URLs de listas M3U (evita escrever com o comando).
- Ficheiros vão para a memória interna do Sintonia: sem permissões de armazenamento, funciona em qualquer
  Fire OS / Google TV (o LocalSend + pasta Download falha no Fire OS 8 sem "acesso a todos os ficheiros",
  e muitas Fire TV não têm seletor de ficheiros).
- Servidor HTTP leve dentro da app, só ativo enquanto o ecrã estiver aberto (segurança: só rede local).

### 2. Configuração de listas por país (ficheiro do utilizador) — 📺💻 ambos
Gerador do ficheiro: `ferramentas/configurador-listas.html` (também publicado como página "Configurador de Listas").
Enviada pelo "Receber do telemóvel" (colar texto / ficheiro .txt) ou por URL (relida periodicamente —
editar o ficheiro no servidor atualiza a TV). Cada envio **substitui** todas as listas (o ficheiro é a
fonte de verdade); favoritos e grupos escondidos mantêm-se por lista.

Formato:
```
###M3U Lists###          ← título/comentário (ignorado)
https://...              ← antes de qualquer [XX]: lista SEM VPN

[PT]                     ← secção de país → usa pt.conf
01                       ← linha que não é URL = nome da lista seguinte
https://...
02
https://...

[ES]
01
https://...              ← usa es.conf

[UK]
01
https://...              ← usa uk.conf
```
Regras:
- Nome na app: "PT · 01"; URL sem nome antes → "Lista N".
- Código do país → ficheiro `<código em minúsculas>.conf` (ou `.ovpn` no plano B).
- Falta o `.conf` da região → lista mostra "falta es.conf" e não abre (sem falsa proteção).
- "País onde estou" = **Automático por IP** (com opção de fixar à mão): listas do país detetado não
  ligam VPN; as restantes ligam o `.conf` do seu país (o mesmo ficheiro serve em casa e em viagem).
  - Deteção: com a VPN desligada, pedir `https://www.cloudflare.com/cdn-cgi/trace` → linha `loc=XX`
    (sem conta/chave). Ao arrancar e quando a rede muda; se falhar, usar o último país conhecido.
  - Decide pelo IP (é o que os bloqueios geográficos usam), não pela localização física. Ex.: router 5G
    da autocaravana com cartão PT em roaming na UE (home routing) → IP PT em qualquer país → listas [PT]
    sem VPN; listas [ES] ligam es.conf mesmo em Espanha. Wi-Fi local / cartão local → IP do país.
  - Mostrar no topo: "IP: PT · VPN desligada" / "IP: PT · VPN ES ligada".
- **Modo manual da VPN** (botão "VPN" no topo e no menu do OK do leitor):
  - Automática (segue lista + país do IP) · Desligada (nunca liga) · fixar um país (ES, UK, PT… — os
    `.conf` existentes).
  - A escolha manual mantém-se (mesmo depois de fechar a app) até voltar a "Automática".
  - Uma VPN de cada vez: a escolha manual ganha à da lista; lista de outro país mostra
    "esta lista pede UK".
  - Depois de ligar, confirmar pelo `loc=` e mostrar "IP: ES ✓" (a VPN está mesmo a funcionar).
  - Topo: "VPN: ES (manual)" / "VPN: UK (auto)" / "VPN: desligada".
- Lista escolhida sem país → VPN desligada.

### 3. VPN por região — decisão: **WireGuard embutido** (favorito) — 📺 APK primeiro; 💻 desktop depois (outra forma de ligar)
- Biblioteca oficial WireGuard para Android, licença permissiva (Apache 2.0/MIT, confirmar antes de
  distribuir) → uma só app, sem GPL, leve na Fire TV.
- Um `.conf` por região (servidor fixo num país). Fornecedores com `.conf`: Surfshark (VPN → Manual setup →
  Router → WireGuard), NordVPN (Nord Account → Manual configuration, desde out/2026), Proton VPN, IPVanish,
  PrivateVPN, IVPN, Hide.me…
- Alguns fornecedores expiram os ficheiros (ex.: 30 dias) → substituir é só reenviar pelo telemóvel.

**Plano B — só para fornecedores que deem apenas `.ovpn`:**
*B1 — controlar o "OpenVPN for Android" (de.blinkt.openvpn), app instalada à parte*
- Único cliente OpenVPN com controlo remoto público. Na Fire TV instala-se à parte (Downloader;
  F-Droid/GitHub) — testar primeiro um `.ovpn` à mão.
- API AIDL (serviço `de.blinkt.openvpn.api.IOpenVPNAPIService`): `prepare(pkg)` (autorização uma vez),
  `prepareVPNService()`, `addNewVPNProfile(nome, editável, config)`, `startProfile(uuid)`, `disconnect()`,
  `registerStatusCallback` (estados ex.: CONNECTED, AUTH_FAILED). Copiar os .aidl tal como estão no
  projeto ics-openvpn (a ordem dos métodos importa); manifest: `<queries><package android:name="de.blinkt.openvpn"/></queries>`.
- Alternativa simples por Intents: atividades `de.blinkt.openvpn.api.ConnectVPN` /
  `de.blinkt.openvpn.api.DisconnectVPN`, extra `de.blinkt.openvpn.api.profileName` (o perfil tem de existir).
- OpenVPN Connect e apps de fornecedores (Nord, Surfshark…) não têm API pública.

*B2 — OpenVPN dentro do Sintonia*
- `VpnService` próprio + motor OpenVPN embutido (ics-openvpn / openvpn3): uma só app, sem dependências.
- Mais trabalho (binários nativos por arquitetura, gestão de ligação); licença GPL — só pesa se a
  app for distribuída.
