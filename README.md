# Treino

App Android do treino da semana (Treino Trinca adaptado).

- `web/index.html`: o app em si (treinos, ilustrações, cronômetro e descanso entre séries).
- `android/`: o "casco" Android que mostra a página e adiciona o que o navegador não faz sozinho:
  aviso de fim de descanso como notificação (mesmo com o celular em outro app), voz,
  vibração, tela sempre ligada e atualização pelo próprio app.

## Como sai uma versão nova

1. Altere `web/index.html` (ou o projeto Android) e faça o commit na `main`.
2. O GitHub Actions compila o APK e publica uma *Release* `vN`, com a mensagem do commit como novidades.
3. Ao abrir o app, ele consulta a última Release; se houver versão nova, pergunta se quer atualizar,
   baixa e abre o instalador. Também há o botão **Verificar atualização** no fim da página.

## Assinatura

O APK é assinado com `android/treino-release.jks` (PKCS12, protegido por senha).
A senha fica no segredo `KEYSTORE_PASSWORD` do repositório. Não troque a chave:
o Android só instala uma atualização por cima se a assinatura for a mesma.

Exercise data and illustrations by RepDB (repdb.co).
