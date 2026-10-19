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
- Testes do leitor M3U: `./gradlew testReleaseUnitTest`
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
  Adapters.kt, Prefs.kt, Device.kt
res/layout/exo_player_control_view.xml  menu do OK do Media3 + botão "Canais"
```

## Limitações conhecidas
- Canais com Widevine sem URL de licença não abrem (a M3UPT não usa).
- Alguns canais só funcionam com IP português (nota da própria M3UPT).
- Guia só em linha "agora / a seguir" (sem grelha completa).
- Num Fire TV Stick mais fraco, o primeiro carregamento do guia pode demorar ~1 min (depois fica em cache).
