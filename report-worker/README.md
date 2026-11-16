# Reportes de usuarios → issues de GitHub

Worker de Cloudflare que recibe los reportes de Atril y crea la issue con un
token **que nunca sale del servidor**.

## Por qué hace falta

La API de GitHub exige un token para crear issues. Meterlo en el APK lo haría
extraíble, así que la app habla con este worker y el worker habla con GitHub.

Sin endpoint configurado, el botón de Atril sigue funcionando: abre GitHub con
el reporte ya rellenado y el usuario pulsa *Submit new issue*.

## Despliegue

```bash
cd report-worker
npx wrangler login
npx wrangler secret put GITHUB_TOKEN   # PAT fine-grained: Issues write en davidadrianrg/atril
npx wrangler secret put ATRIL_KEY      # cadena aleatoria larga
npx wrangler deploy
```

Copia la URL desplegada (`https://atril-reports.<subdominio>.workers.dev`) y
compila la app con:

```bash
./gradlew :app:assembleRelease \
  -Patril.reportUrl="https://atril-reports.<subdominio>.workers.dev" \
  -Patril.reportKey="<la misma ATRIL_KEY>"
```

En CI se pueden pasar como variables de entorno `ATRIL_REPORT_URL` y
`ATRIL_REPORT_KEY`.

## Etiqueta

Las issues se crean con la etiqueta `user-report`; conviene crearla antes en el
repositorio o GitHub la ignorará silenciosamente.

## Seguridad

- El token de GitHub solo vive en el worker (`wrangler secret`).
- `ATRIL_KEY` va dentro del APK y es extraíble; sirve como filtro básico, no
  como autenticación fuerte.
- Sin `ATRIL_KEY` el worker responde 503 (cerrado). Para aceptar peticiones
  anónimas a propósito, define la variable `ALLOW_ANONYMOUS = "true"`.
- Activa el namespace KV `RATE`: limita a 5 reportes/hora por IP, aplica un
  tope global diario (`DAILY_LIMIT`, 30 por defecto) y deduplica reportes
  idénticos durante 7 días (devuelve la URL de la issue existente).
- Las `@menciones` del texto se neutralizan con un espacio de ancho cero y el
  diagnóstico va en un bloque de código plegado: el contenido no puede
  notificar a nadie ni inyectar Markdown en la issue pública.
- Si hay spam, rota `ATRIL_KEY` y `GITHUB_TOKEN` y publica una app nueva.
