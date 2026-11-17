# Germany Solar

Gerenciamento de funcionários, serviços (de 1 dia e longos, com etapas diárias) e presença para empresa de elétrica e energia solar.

- `app/` – app Android (APK). A tela fica em `app/src/main/assets/index.html`.
- `pc/` – app para Windows (Go). Compilar: `GOOS=windows GOARCH=amd64 go build -ldflags "-s -w -H windowsgui" -o "Germany Solar.exe" .`

## Baixar o APK

Cada envio para o ramo `main` gera o APK automaticamente. Ele fica na página **Releases** do repositório, na versão "ultima-versao".

## Nuvem

O app pode sincronizar os dados entre aparelhos usando um projeto gratuito do Firebase (Firestore + login por e-mail e senha). A configuração é colada dentro do próprio app, em "Conectar à nuvem".
