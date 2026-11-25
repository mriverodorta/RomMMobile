# RomMMobileS tester signup service

The GitHub Pages form sends HTTPS requests to `https://rommmobiles.hkloud.org/api/testers`.
The firewall terminates TLS and connects to this container over HTTP.
Use a LAN address that the reverse proxy can reach. Keep the origin port off public WAN.

## Deploy

Copy this directory to its own path under `/data/stack`. Create the database directory for the container UID:

```sh
install -d -m 0700 -o 10001 -g 10001 data
```

Set `SIGNUP_BIND_IP` in the server's local `.env` to the LAN address.
The default `SIGNUP_PORT` is `8768`. `SIGNUP_DATA_DIR` defaults to `./data`.

```sh
docker compose up -d --build
docker compose ps
```

The API serves `GET /healthz` for health and `POST /api/testers` with CORS preflight for the form.
Allow HTTPS requests from the GitHub Pages site to reach the API, preserving the `Origin` header.
Configure your firewall to keep access to the container port within the LAN.
The frontend handles unreachable API responses and offers email as a fallback.

## Manage testers over SSH

```sh
docker compose exec -T signup python signup.py count
docker compose exec -T signup python signup.py export
```

The export prints one address per line. To paste into a Play Console tester list,
pipe the export to `paste -sd, -`. Review the list before adding it.
For a deletion request, pass the email over standard input to
`docker compose exec -T signup python signup.py delete`.

The database stores only normalized addresses and submission timestamps. It accepts
at most 20 new addresses per rolling hour and 250 total. Duplicate addresses do
not consume additional slots. The service limits requests, rejects unexpected
origins and oversized bodies, and has no public export route. These limits cap
storage and request work even when someone sends automated requests.
