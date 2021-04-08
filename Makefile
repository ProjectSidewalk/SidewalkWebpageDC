.PHONY: dev docker-up docker-up-db docker-run docker-stop ssh stage-import

db ?= sidewalk
dir ?= ./
args ?=

dev: | docker-up-db docker-run

docker-up:
	@docker-compose up -d

docker-up-db:
	@docker-compose up -d db

docker-stop:
	@docker-compose stop
	@docker-compose rm -f

docker-run:
	@docker-compose run --rm --service-ports --name projectsidewalk-dc-web web /bin/bash

ssh:
	@docker exec -it projectsidewalk-dc-$${target} /bin/bash

import-dump:
	@docker exec -it projectsidewalk-dc-db sh -c "/opt/import-dump.sh $(db)"
