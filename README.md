# Sintonia

Leitor de listas M3U (TV e rádio) simples, para **Google TV / Android TV, Fire TV, telemóvel Android** e
**computador (Linux, Windows, Mac)**. Vem configurado com a lista [M3UPT](https://github.com/LITUATUI/M3UPT)
(canais portugueses públicos e oficiais); qualquer outra lista M3U pode ser usada nas Definições.

## Transferir

| Plataforma | Ficheiro |
|---|---|
| TV e telemóvel Android | [Sintonia.apk — versão mais recente](https://github.com/Qwerty-PT/sintonia/releases/latest) |
| Linux (Ubuntu, Debian, Mint) | `.deb` em [Sintonia desktop](https://github.com/Qwerty-PT/sintonia/releases?q=desktop) |
| Linux (outras) | `.AppImage` (x64 e arm64), na mesma página |
| Windows | `.exe`, na mesma página |
| Mac | `.dmg` (Apple Silicon e Intel), na mesma página |

- **Fire TV:** na app *Downloader*, abrir
  `https://github.com/Qwerty-PT/sintonia/releases/latest` e escolher o `.apk`.
- **Linux .deb:** `sudo apt install ./Sintonia-<versão>-linux-amd64.deb`
- **AppImage:** `chmod +x Sintonia-*.AppImage` e abrir (Ubuntu 22.04+: pode precisar de `sudo apt install libfuse2`).
- **Windows / Mac:** não são assinados — Windows: "Mais informações → Executar mesmo assim"; Mac: botão direito → Abrir.

O APK atualiza-se sozinho (propõe a versão nova ao abrir). A versão de computador avisa quando há uma nova.

## O que faz

- Grupos, favoritos, grupos escondidos, guia de programação (EPG) com o programa atual e seguintes
- Lista de canais sobre o vídeo, com pré-visualização numa janela
- Verificação automática de canais: marca os que não estão a funcionar ou estão bloqueados no país
- HLS, DASH e DRM ClearKey; cabeçalhos por canal (User-Agent, Referer, Origin)

Documentação técnica (compilar, assinatura, formatos, estrutura): [LEIA-ME.md](LEIA-ME.md).
