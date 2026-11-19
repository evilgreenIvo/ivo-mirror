# IVO Mirror

Receba a tela do iPhone num celular Android e use como fonte no **[IVO LIVE](https://github.com/evilgreenIvo/ivo-live)**.

No iPhone você usa o **Espelhar Tela** de sempre (Central de Controle). O IVO Mirror recebe a imagem e o som e manda para o IVO LIVE, no mesmo celular ou em outro da mesma Wi-Fi, como a fonte **Outro celular**.

<p align="center">
  <img src="docs/tela.png" width="300" alt="Tela do IVO Mirror desligado, com o botão Ligar e o passo a passo">
  &nbsp;
  <img src="docs/qualidade.png" width="300" alt="Escolha de qualidade Normal ou Alta">
</p>

## Baixar

Baixe o APK mais novo em **[Releases](https://github.com/evilgreenIvo/ivo-mirror/releases/latest)** e instale no celular Android (Android 10 ou mais novo, processador de 64 bits).

## Como usar

1. Deixe o iPhone e o celular Android na mesma Wi-Fi.
2. Abra o IVO Mirror e toque em **Ligar**.
3. No iPhone, abra a Central de Controle, toque em **Espelhar Tela** e escolha **IVO Mirror (nome do celular)**.
4. No IVO LIVE: **+ Fonte › Outro celular › "iPhone · …"**.

Se algo der errado, toque em **Enviar relatório**: sai um arquivo .txt só com o que importa para achar o problema.

## O que dá e o que não dá

- **Qualidade Normal ou Alta.** Normal (1080p) é leve. Alta (1440p) deixa a imagem do iPhone em pé com cerca do dobro da nitidez, mas pesa mais no celular Android.
- **Cerca de 30 quadros por segundo.** É o limite do espelhamento do iPhone. O app já pede 60, e o iPhone responde com 30.
- **Apps protegidos aparecem pretos** (Netflix, Disney+, Apple TV+, Prime Video). É proteção contra cópia feita pelo próprio iPhone.
- **Só o som do sistema.** O espelhamento não manda o microfone do iPhone.
- **Não controla o iPhone** pelo Android.

## Compilar

Projeto Android (Kotlin + C via NDK, só `arm64-v8a`). Precisa do Android SDK, do NDK e do Gradle. A parte nativa já vem com o UxPlay, a libplist e a `libcrypto.a` do OpenSSL compilada para Android 29 em `app/src/main/cpp`.

```
gradle assembleDebug
```

Sem a chave de assinatura (que não fica no repositório), `assembleRelease` gera um APK sem assinatura.

## Licença

Código aberto sob a **GPL-3.0-or-later** (arquivo [LICENSE](LICENSE)). As partes de terceiros e suas licenças estão em [NOTICE.md](NOTICE.md).

iPhone, iOS e AirPlay são marcas da Apple Inc. Este projeto não é da Apple nem tem ligação com ela.
