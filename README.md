# PromptCue

Teleprompter flutuante para gravar vídeo no Android. Uma faixa fica por cima da câmera nativa (ou de qualquer outro app) com o texto rolando sozinho, logo abaixo da lente frontal, pra você ler sem desviar o olhar de quem vai assistir.

**A faixa gira junto com o aparelho** — inclusive quando o app de câmera trava a tela em pé e só gira os próprios ícones. É pra isso que o PromptCue existe: gravar em 9:16 ou em 16:9 deitado sem o texto ficar de lado.

## Download

Baixe o APK na [página de releases](https://github.com/caducosilva/teleprompter-overlay/releases/latest) e instale direto no Android (é preciso permitir instalação de fontes desconhecidas).

## Como funciona

1. Abra o PromptCue, cole o roteiro e toque em **Abrir teleprompter**.
2. O app sai da frente e deixa só a faixa flutuando na tela.
3. Abra a câmera e toque em play na faixa — o texto rola sozinho.
4. Pra editar o roteiro, toque no ícone do PromptCue no launcher.

## Recursos

- **Gira com o aparelho, não com a tela.** A orientação vem da gravidade, então deitar o celular deita a faixa mesmo com a rotação automática desligada ou com a câmera segurando a tela em pé.
- **Tamanho fixo, posição livre.** Dois presets de tamanho (em pé e deitado) pra faixa nunca mudar de forma no meio da gravação; arraste pela alça ⠿ pra mudar de lugar. Cada orientação lembra a posição onde foi deixada.
- **Nasce na altura do olho.** A posição padrão é colada no furo da câmera frontal, medido pelo recorte real da tela — no topo em pé, na lateral quando a tela gira. O botão de recentrar volta pra lá.
- **Dedo manda mais que o automático.** Dá pra rolar na mão a qualquer momento, mesmo rolando sozinho; ao soltar, a rolagem continua exatamente de onde o dedo parou (sem inércia atrapalhando).
- Velocidade e tamanho da fonte ajustáveis pela própria faixa, salvos entre sessões.
- Sem anúncios, sem rede, sem telemetria: o app não pede permissão de internet.

## Stack

Android nativo em Kotlin, sem dependência de UI de terceiros. A faixa é uma janela `TYPE_APPLICATION_OVERLAY` gerenciada por um foreground service; a rolagem é um `Choreographer.FrameCallback` e a orientação vem do sensor de gravidade.

A versão anterior era Flutter + `flutter_overlay_window`. Foi reescrita porque aquele plugin não tem como girar a faixa quando a tela não gira — que é justamente o caso de uso principal.

## Rodando localmente

```bash
./gradlew installDebug
```

Requer o Android SDK (`sdk.dir` em `local.properties`) e JDK 17+. A permissão de "Aparecer sobre outros apps" é pedida na primeira vez que a faixa é aberta.

Para assinar o release, crie um `key.properties` na raiz apontando pro keystore em `app/`:

```properties
storePassword=...
keyPassword=...
keyAlias=promptcue
storeFile=teleprompter_overlay-release.jks
```

Sem esse arquivo o build de release usa a chave de debug.

## Licença

MIT — veja [LICENSE](LICENSE).

## Autor

**CADUCOSILVA** — [Carlos Eduardo (@caducosilva)](https://github.com/caducosilva)
Contato: abobicarlo@gmail.com

Doações via PIX (chave aleatória): `f74458dc-2a36-49bd-9250-1cef4365ebb8`
