import os

from signup import database_path, make_app

os.umask(0o077)
application = make_app(database_path(), "https://mriverodorta.github.io")
