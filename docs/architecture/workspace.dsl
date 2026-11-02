workspace "UrbanPulse" "C4 Model" {

    !identifiers hierarchical
    # Every relationship is written explicitly at the level where it is shown, so Structurizr
    # does not add implied arrows (duplicates, or web -> backend skipping nginx).
    !impliedRelationships false

    model {
        ### System Actors (roles as in the users.role enum of V2__identity)
        citizen = person "Citizen" "Reports incidents with location and evidence, follows their status and receives notifications." {
            tags "Citizen"
        }

        operator = person "Municipal Operator" "Validates, rejects, classifies, prioritizes and assigns incidents." {
            tags "MunicipalOperator"
        }

        analyst = person "Analyst" "Explores indicators, territorial patterns and reports." {
            tags "Analyst"
        }

        admin = person "Administrator" "Manages users, roles, categories, departments and data sources." {
            tags "Administrator"
        }

        technician = person "Technician" "Handles assigned work, updates its progress and documents the resolution." {
            tags "Technician"
        }

        ### System Context - UrbanPulse
        urbanpulse = softwareSystem "UrbanPulse" "Platform for managing urban incidents reported by citizens and enriched with contextual data from the city of Málaga." {
            tags "System"

            ### 1. CONTAINER: WEB FRONTEND (ADR-0006, ADR-0007, ADR-0016)
            frontend_web = container "Web Frontend" "SPA for operators, technicians, administrators and analysts: maps, dense incident lists and dashboards. Built with Vite and served by nginx." "React + Vite + TypeScript" {
                tags "FrontendWeb"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0006-frontend-web-technology.md

                web_router = component "Router and Views" "React Router for navigation and URL-driven filters (area, date, status)." "React Router"
                web_ui = component "UI Components and Map" "Dense incident lists, management forms and the incident map over the self-hosted base map." "React / MapLibre GL JS (react-map-gl)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0016-self-hosted-osm-base-map.md
                }
                web_state = component "State Manager (Server State)" "Client-side cache, retries, and invalidation of incidents and evidences." "React Query"
                web_shared = component "Shared Packages (Monorepo)" "packages/domain (entities, enums, business rules, validation schemas), packages/api-client (typed HTTP client generated from OpenAPI, token refresh) and packages/ui, shared with the mobile app." "TypeScript (pnpm workspaces)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0007-client-application-architecture.md
                }

                web_router -> web_ui "Renders"
                web_ui -> web_state "Requests data"
                web_state -> web_shared "Delegates HTTP requests"
            }

            ### 2. CONTAINER: MOBILE FRONTEND (ADR-0005, ADR-0007, ADR-0016)
            frontend_mobile = container "Mobile Frontend" "App for citizens to report and follow incidents." "React Native + Expo (EAS)" {
                tags "FrontendMobile"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0005-mobile-application-technology.md

                mobile_ui = component "Mobile Views and Map" "Report forms, navigation and map interaction over the self-hosted base map (requires an Expo development build)." "React Navigation / MapLibre React Native" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0016-self-hosted-osm-base-map.md
                }
                mobile_native = component "Native Modules (Expo)" "Access to camera, GPS, file system, and secure token storage." "Expo Modules"
                mobile_state = component "State and Offline Manager" "Server state cache and local persistence of offline drafts (AsyncStorage)." "React Query + AsyncStorage"
                mobile_shared = component "Shared Packages (Monorepo)" "The same packages/domain, packages/api-client and packages/ui as the web app." "TypeScript (pnpm workspaces)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0007-client-application-architecture.md
                }

                mobile_ui -> mobile_native "Uses camera, GPS, file system and secure storage"
                mobile_ui -> mobile_state "Requests data and saves drafts"
                mobile_state -> mobile_shared "Delegates HTTP requests"
            }

            ### 3. CONTAINER: BACKEND API (ADR-0008, ADR-0010)
            # One component per module package es.uma.urbanpulse.<module>, exactly the module list of
            # ADR-0010. Packages only organise plain MVC code: no enforced boundaries, everything is
            # public, services call each other and entities keep JPA relations across modules.
            # Controllers, services and repositories live inside each module, so they are not components.
            backend = container "Backend API" "Contract-first REST API (OpenAPI) built as a modular monolith; manages transactions and scheduled background jobs. The dev, demo and perf Spring profiles load sample datasets through Flyway on startup; the bootstrap-admin profile runs it as a one-off process that creates the first administrator." "Spring Boot (Java)" {
                tags "Backend"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0008-backend.md

                identity = component "identity" "Users, roles and permissions; sign-up, login with in-memory attempt limits, short-lived JWTs, rotating hashed refresh tokens and a TOTP second factor for staff (ADR-0021); audit log." "Spring Security / Spring Data JPA" {
                    tags "Identity"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0004-authentication-strategy.md
                }
                incidents = component "incidents" "The incident as the central entity: reporting, location, categories, priority, lifecycle with allowed transitions, audited status history, assignments, comments and evidence metadata." "Spring Web / Spring Data JPA" {
                    tags "Incidents"
                }
                urbancontext = component "urbancontext" "External data: adapters for Open Data Málaga, OpenStreetMap, Open-Meteo and TomTom; loading of reference data, geocoding and asynchronous enrichment of incidents with timeouts and graceful degradation." "Spring Service / Spring Data JPA" {
                    tags "UrbanContext"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0009-urban-data-source.md
                }
                geospatial = component "geospatial" "Location and territory: districts, neighbourhoods, urban assets and spatial queries over local data." "Spring Service / Spring Data JPA / PostGIS" {
                    tags "Geospatial"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0003-geospatial-data-management.md
                }
                notifications = component "notifications" "Notifications created on incident status changes and their delivery status; email (Brevo) and SMS (Twilio) adapters behind one sender interface per channel." "Spring Service / Spring Data JPA" {
                    tags "Notifications"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0019-notification-delivery-providers.md
                }
                analytics = component "analytics" "Read-only indicators, zone analysis and reports; owns no tables." "Spring Service" {
                    tags "Analytics"
                }

                incidents -> identity "Checks users and roles; writes audit entries in the same transaction"
                incidents -> urbancontext "Requests context enrichment; it runs asynchronously and never blocks the report (ADR-0009)" "@Async service call" {
                    tags "Async"
                }
                incidents -> notifications "Creates notifications on status changes"
                incidents -> geospatial "Spatial filters on incident locations (radius, district)"
                urbancontext -> geospatial "Loads reference data; resolves district and nearest asset"
                notifications -> identity "Gets recipients"
                analytics -> incidents "Reads incident data" "Read-only"
                analytics -> geospatial "Zone analysis" "Read-only"
            }

            ### 4. CONTAINER: DATABASE (ADR-0001, ADR-0003, ADR-0014)
            postgres = container "PostgreSQL" "Relational data with referential integrity, spatial geometry and JSONB. One schema shared by all modules, versioned with Flyway migrations." "PostgreSQL 18 + PostGIS 3.6 (postgis/postgis, Debian)" {
                tags "Database"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0001-database.md

                db_relational = component "Relational Schema" "Table groups created by Flyway: identity (users, refresh_token, audit_log), incidents (incident, location, category, department, assignment, status_change, comment, attachment), urbancontext (urban_context, external_observation, data_source_config; also urban_asset and incident_asset, used by geospatial), notifications (notification)." "SQL tables" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0014-database-migrations-flyway.md
                }
                db_postgis = component "Spatial Engine (PostGIS)" "geography(Point, 4326) in location.coordinates and urban_asset.geometry with GiST indexes; distance and containment operators." "PostGIS extension" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0003-geospatial-data-management.md
                }
                db_jsonb = component "JSONB Columns" "Semi-structured fields: audit_log.details, status_change.associated_data and urban_asset.metadata." "JSONB"
            }

            ### 5. CONTAINER: CACHE (ADR-0012, ACCEPTED, NOT BUILT YET)
            # The decided target. Its "Implementation status" section lists when Redis comes in; until
            # then no deployment environment runs it.
            redis = container "Redis Cache" "Cache-aside with TTL on every entry: external responses, resolved geodata per zone and dashboard aggregates. Safe to lose." "Redis" {
                tags "Cache"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0012-cache-redis.md
            }

            ### 6. CONTAINER: REVERSE PROXY (ADR-0017, ACCEPTED)
            nginx = container "Reverse Proxy / Web Server" "Single published entry point in production: serves the web build and the base map tiles (/tiles/) and proxies /api to the backend." "nginx" {
                tags "ReverseProxy"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0017-reverse-proxy.md
            }

            ### 7. CONTAINER: EVIDENCE STORAGE (ADR-0013, ACCEPTED)
            evidence_storage = container "Evidence Storage" "Evidence binaries outside PostgreSQL in the evidence_data volume mounted in the backend, accessed only through StorageService with server-generated keys; S3-compatible storage later." "Docker named volume (LocalFsStorage)" {
                tags "Storage"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0013-evidence-storage.md
            }

            ### 8. CONTAINER: BASE MAP TILES BUILDER (ADR-0016, ACCEPTED)
            tiles_builder = container "Tiles Builder" "One-off admin process run every 14 days: downloads the Andalucía OSM extract, clips it to Málaga and builds malaga.pmtiles (zoom 0-14)." "Planetiler (Docker, compose profile tiles)" {
                tags "AdminProcess"
                url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0016-self-hosted-osm-base-map.md
            }
        }

        ### External entities / APIs
        openstreet = softwareSystem "OpenStreetMap" "Geocoding and reverse geocoding (Nominatim) and map features and urban assets (Overpass). Called only by the backend." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0002-open-street-map.md
        }

        geofabrik = softwareSystem "Geofabrik" "Regional OpenStreetMap extracts (Andalucía, .osm.pbf) used to build the base map." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0016-self-hosted-osm-base-map.md
        }

        opendata = softwareSystem "Open Data Málaga" "Primary source of districts, neighborhoods and urban facilities (datos.malaga.eu)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0009-urban-data-source.md
        }

        openmeteo = softwareSystem "Open-Meteo" "Current weather by coordinates (WMO weather code, wind, precipitation). No API key." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0018-weather-and-traffic-providers.md
        }

        tomtom = softwareSystem "TomTom Traffic" "Traffic Flow Segment Data: current and free-flow speed of the road nearest to a point." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0018-weather-and-traffic-providers.md
        }

        brevo = softwareSystem "Brevo" "Transactional email API (EU-based provider)." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0019-notification-delivery-providers.md
        }

        twilio = softwareSystem "Twilio" "SMS API (Programmable Messaging)." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/main/docs/adr/0019-notification-delivery-providers.md
        }

        llm = softwareSystem "LLM Provider" "External model for classification, summarization, and RAG (to be decided; no module yet)." {
            tags "ExternalAPI,Future"
        }

        ### Level 1: system context
        citizen -> urbanpulse "Reports incidents and checks their status"
        operator -> urbanpulse "Validates, prioritizes, and assigns incidents"
        analyst -> urbanpulse "Views indicators and patterns"
        admin -> urbanpulse "Manages users and catalogs"
        technician -> urbanpulse "Consults assigned incidents and updates their progress"
        urbanpulse -> openstreet "Geocoding and assets" "HTTPS"
        urbanpulse -> geofabrik "Downloads the OSM extract for the base map" "HTTPS"
        urbanpulse -> opendata "Authoritative urban data" "HTTPS"
        urbanpulse -> openmeteo "Current weather" "HTTPS"
        urbanpulse -> tomtom "Current traffic flow" "HTTPS"
        urbanpulse -> brevo "Sends notification emails" "HTTPS"
        urbanpulse -> twilio "Sends notification SMS" "HTTPS"
        urbanpulse -> llm "Intelligent assistance" "HTTPS" {
            tags "Future"
        }
        brevo -> citizen "Delivers notification emails" "Email"
        twilio -> citizen "Delivers notification SMS" "SMS"

        ### Level 2: containers
        citizen -> urbanpulse.frontend_mobile "Reports incidents and attaches evidence"
        operator -> urbanpulse.frontend_web "Filters and reviews incidents"
        analyst -> urbanpulse.frontend_web "Views dashboards"
        admin -> urbanpulse.frontend_web "Manages accounts and roles"
        technician -> urbanpulse.frontend_web "Consults assigned incidents and updates their progress"

        urbanpulse.frontend_web -> urbanpulse.nginx "Loads the SPA, calls /api and loads the base map (/tiles/)" "HTTPS"
        urbanpulse.frontend_mobile -> urbanpulse.nginx "Calls /api and loads the base map (/tiles/)" "HTTPS"
        urbanpulse.nginx -> urbanpulse.backend "Proxies /api" "HTTP (internal network)"
        urbanpulse.tiles_builder -> geofabrik "Downloads the Andalucía extract" "HTTPS"
        urbanpulse.tiles_builder -> urbanpulse.nginx "Publishes malaga.pmtiles to the tiles volume" "Docker named volume"
        urbanpulse.backend -> urbanpulse.postgres "Reads and writes; applies Flyway migrations on startup" "JDBC"
        urbanpulse.backend -> urbanpulse.redis "Cache-aside with TTL"
        urbanpulse.backend -> urbanpulse.evidence_storage "Stores and reads evidence binaries" "File system (volume mount)"
        urbanpulse.backend -> openstreet "Geocoding and assets" "HTTPS"
        urbanpulse.backend -> opendata "Authoritative urban data" "HTTPS"
        urbanpulse.backend -> openmeteo "Current weather" "HTTPS"
        urbanpulse.backend -> tomtom "Current traffic flow" "HTTPS"
        urbanpulse.backend -> brevo "Sends notification emails" "HTTPS (REST API)"
        urbanpulse.backend -> twilio "Sends notification SMS" "HTTPS (REST API)"
        urbanpulse.backend -> llm "Intelligent assistance" "HTTPS" {
            tags "Future"
        }

        ### Level 3: backend components
        urbanpulse.nginx -> urbanpulse.backend.identity "Proxies /api (auth, users, roles)" "HTTP/JSON"
        urbanpulse.nginx -> urbanpulse.backend.incidents "Proxies /api (incidents)" "HTTP/JSON"
        urbanpulse.nginx -> urbanpulse.backend.urbancontext "Proxies /api (context, data sources)" "HTTP/JSON"
        urbanpulse.nginx -> urbanpulse.backend.geospatial "Proxies /api (districts, assets, spatial search)" "HTTP/JSON"
        urbanpulse.nginx -> urbanpulse.backend.notifications "Proxies /api (in-app notifications)" "HTTP/JSON"
        urbanpulse.nginx -> urbanpulse.backend.analytics "Proxies /api (reports)" "HTTP/JSON"

        urbanpulse.backend.identity -> urbanpulse.postgres "users, refresh_token (hashed), audit_log" "JPA/JDBC"
        urbanpulse.backend.incidents -> urbanpulse.postgres "Incident tables (ACID)" "JPA/JDBC"
        urbanpulse.backend.urbancontext -> urbanpulse.postgres "urban_context, external_observation, data_source_config" "JPA/JDBC"
        urbanpulse.backend.geospatial -> urbanpulse.postgres "urban_asset, incident_asset; PostGIS spatial queries" "JPA/JDBC"
        urbanpulse.backend.notifications -> urbanpulse.postgres "notification" "JPA/JDBC"
        urbanpulse.backend.analytics -> urbanpulse.postgres "Aggregate queries" "Read-only"
        urbanpulse.backend.urbancontext -> urbanpulse.redis "Caches external responses (TTL)"
        urbanpulse.backend.geospatial -> urbanpulse.redis "Caches resolved geodata per zone (TTL)"
        urbanpulse.backend.analytics -> urbanpulse.redis "Caches dashboard aggregates (TTL)"
        urbanpulse.backend.incidents -> urbanpulse.evidence_storage "Stores and reads evidence binaries through StorageService"
        urbanpulse.backend.urbancontext -> opendata "Queries authoritative data" "HTTPS"
        urbanpulse.backend.urbancontext -> openstreet "Geocoding and asset fallback" "HTTPS"
        urbanpulse.backend.urbancontext -> openmeteo "Current weather at the incident" "HTTPS"
        urbanpulse.backend.urbancontext -> tomtom "Traffic flow near the incident" "HTTPS"
        urbanpulse.backend.notifications -> brevo "Sends transactional email" "HTTPS (REST API)"
        urbanpulse.backend.notifications -> twilio "Sends SMS" "HTTPS (Twilio Java SDK)"

        ### Level 3: database components
        urbanpulse.backend -> urbanpulse.postgres.db_relational "Each module reads and writes its own tables; analytics only reads" "JPA/JDBC"
        urbanpulse.backend -> urbanpulse.postgres.db_postgis "Spatial queries from geospatial" "Native SQL"
        urbanpulse.backend -> urbanpulse.postgres.db_jsonb "Audit details, status data and asset metadata" "JPA/JDBC"

        ### Level 3: client components
        operator -> urbanpulse.frontend_web.web_router "Filters and reviews dense incident lists"
        operator -> urbanpulse.frontend_web.web_ui "Inspects evidence on maps"
        analyst -> urbanpulse.frontend_web.web_router "Views statistical dashboards"
        admin -> urbanpulse.frontend_web.web_router "Manages accounts and roles"
        technician -> urbanpulse.frontend_web.web_router "Consults assigned incidents and updates their progress"
        urbanpulse.frontend_web.web_shared -> urbanpulse.nginx "Calls /api" "HTTPS/JSON"
        urbanpulse.frontend_web.web_ui -> urbanpulse.nginx "Loads base map tiles, style, fonts and sprites (/tiles/)" "HTTPS (PMTiles)"

        citizen -> urbanpulse.frontend_mobile.mobile_ui "Reports incidents and attaches evidence"
        urbanpulse.frontend_mobile.mobile_shared -> urbanpulse.nginx "Calls /api" "HTTPS/JSON"
        urbanpulse.frontend_mobile.mobile_ui -> urbanpulse.nginx "Loads base map tiles (pmtiles://)" "HTTPS (PMTiles)"

        ### Deployment: what runs where (ADR-0011)
        # Structurizr copies a container relationship to the instances of an environment when both
        # ends are deployed there. The relationships written below exist only in one environment.

        # The development setup: backend/docker-compose.yaml, every port on 127.0.0.1.
        development = deploymentEnvironment "Development" {
            developer_machine = deploymentNode "Developer machine" "A team member's computer" "Windows, macOS or Linux" {
                compose = deploymentNode "Docker Compose" "backend/docker-compose.yaml, project urbanpulse" "Docker Engine" {
                    postgres_service = deploymentNode "postgres" "Published on 127.0.0.1:5432" "postgis/postgis:18-3.6" {
                        postgres_instance = containerInstance urbanpulse.postgres
                    }
                    backend_service = deploymentNode "backend" "Profile app, published on 127.0.0.1:8080. It can run from the IDE instead." "Image built from backend/Dockerfile" {
                        backend_instance = containerInstance urbanpulse.backend
                    }
                    evidence_volume = deploymentNode "evidence_data" "Mounted in backend at /app/storage" "Docker named volume" {
                        evidence_instance = containerInstance urbanpulse.evidence_storage
                    }
                    tiles_service = deploymentNode "tiles-builder" "Profile tiles, run once with docker compose run" "Image built from tiles/Dockerfile" {
                        tiles_instance = containerInstance urbanpulse.tiles_builder
                    }
                    postgres_volume = infrastructureNode "postgres-data" "Mounted in postgres at /var/lib/postgresql" "Docker named volume"
                    sources_volume = infrastructureNode "tiles-sources" "The OpenStreetMap downloads, reused between runs" "Docker named volume"
                }
                vite = infrastructureNode "Vite development server" "pnpm dev:web on localhost:5173. Serves the SPA and frontend/apps/web/public/tiles, and proxies /api to the backend (API_PROXY_TARGET)." "Vite on Node.js"
                developer_browser = deploymentNode "Browser" "Runs the staff web app" "Chrome, Firefox, Edge or Safari" {
                    web_instance = containerInstance urbanpulse.frontend_web
                }
            }
            device = deploymentNode "Phone or emulator" "Runs the citizen app" "Android or iOS, Expo development build" {
                mobile_instance = containerInstance urbanpulse.frontend_mobile
            }

            developer_machine.developer_browser.web_instance -> developer_machine.vite "Loads the SPA and calls /api" "HTTP"
            developer_machine.vite -> developer_machine.compose.backend_service.backend_instance "Proxies /api" "HTTP"
            device -> developer_machine.compose.backend_service "The mobile app calls /api directly: the emulator at 10.0.2.2:8080; a physical phone needs the backend run from the IDE" "HTTP"
            developer_machine.compose.postgres_service.postgres_instance -> developer_machine.compose.postgres_volume "Stores the database files" "Volume mount"
            developer_machine.compose.tiles_service.tiles_instance -> developer_machine.compose.sources_volume "Reuses the downloads between runs" "Volume mount"
            developer_machine.compose.tiles_service.tiles_instance -> developer_machine.vite "Writes malaga.pmtiles to frontend/apps/web/public/tiles" "Bind mount"
        }

        # The deployed system decided by ADR-0011 and ADR-0017; #134 builds it. Redis is not in it
        # until ADR-0012 says it comes in.
        single_host = deploymentEnvironment "Single host" {
            server = deploymentNode "Server" "One machine runs the whole system (ADR-0011)" "Linux" {
                compose = deploymentNode "Docker Compose" "docker-compose.yaml at the repository root" "Docker Engine" {
                    web_service = deploymentNode "web" "The only published port, HTTP_PORT, with TLS" "Image built from frontend/Dockerfile (unprivileged nginx)" {
                        nginx_instance = containerInstance urbanpulse.nginx
                    }
                    backend_service = deploymentNode "backend" "Internal network only" "Image built from backend/Dockerfile" {
                        backend_instance = containerInstance urbanpulse.backend
                    }
                    evidence_volume = deploymentNode "evidence_data" "Mounted in backend at /app/storage" "Docker named volume" {
                        evidence_instance = containerInstance urbanpulse.evidence_storage
                    }
                    db_service = deploymentNode "db" "Internal network only" "postgis/postgis" {
                        postgres_instance = containerInstance urbanpulse.postgres
                    }
                    tiles_service = deploymentNode "tiles-builder" "Run once every 14 days" "Image built from tiles/Dockerfile" {
                        tiles_instance = containerInstance urbanpulse.tiles_builder
                    }
                    postgres_volume = infrastructureNode "postgres-data" "Mounted in db at /var/lib/postgresql" "Docker named volume"
                }
            }
            user_browser = deploymentNode "Browser" "Runs the staff web app" "Chrome, Firefox, Edge or Safari" {
                web_instance = containerInstance urbanpulse.frontend_web
            }
            phone = deploymentNode "Phone" "Runs the citizen app; release builds allow HTTPS only" "Android or iOS" {
                mobile_instance = containerInstance urbanpulse.frontend_mobile
            }

            server.compose.db_service.postgres_instance -> server.compose.postgres_volume "Stores the database files" "Volume mount"
        }
    }

    views {
        systemContext urbanpulse "SystemContext" {
            title "System context"
            description "C4 level 1: who uses UrbanPulse and which external systems it depends on."
            include *
            autoLayout lr
        }

        container urbanpulse "Containers" {
            title "Containers"
            description "C4 level 2: the applications and data stores inside UrbanPulse and how they talk to each other."
            include *
            autoLayout lr
        }

        component urbanpulse.backend "Backend_Components" {
            title "Backend components"
            description "C4 level 3: the six modules of the modular monolith (ADR-0010)."
            include *
            autoLayout tb
        }

        component urbanpulse.frontend_web "FrontendWeb_Components" {
            title "Web frontend components"
            description "C4 level 3: the parts of the staff web app."
            include *
            autoLayout tb
        }

        component urbanpulse.frontend_mobile "FrontendMobile_Components" {
            title "Mobile frontend components"
            description "C4 level 3: the parts of the citizen mobile app."
            include *
            autoLayout tb
        }

        component urbanpulse.postgres "Database_Components" {
            title "Database components"
            description "C4 level 3: what the single PostgreSQL schema holds."
            include *
            autoLayout tb
        }

        deployment urbanpulse development "Deployment_Development" {
            title "Deployment: development"
            description "The development setup: backend/docker-compose.yaml with every port on 127.0.0.1, and the Vite development server."
            include *
            autoLayout lr
        }

        deployment urbanpulse single_host "Deployment_SingleHost" {
            title "Deployment: single host"
            description "The deployed system decided by ADR-0011 and ADR-0017, built by #134: nginx is the only published port."
            include *
            autoLayout lr
        }

        # Dynamic views follow the decided architecture, so the clients go through nginx. In
        # development the Vite server (web) or nobody (mobile) stands in its place.
        # A citizen, because staff accounts add the TOTP challenge of ADR-0021 before any token.
        dynamic urbanpulse.backend "Dynamic_SignIn" {
            title "Sign-in and refresh token rotation"
            description "ADR-0004 for a citizen: a 15-minute access token and a refresh token stored only as a hash. Every refresh revokes the token used; presenting it again ends every session. Staff accounts add a TOTP step first (ADR-0021)."
            citizen -> urbanpulse.frontend_mobile "Signs in with email and password"
            urbanpulse.frontend_mobile -> urbanpulse.nginx "POST /api/v1/sessions"
            urbanpulse.nginx -> urbanpulse.backend.identity "Proxies the request"
            urbanpulse.backend.identity -> urbanpulse.postgres "Reads the user by email (the attempt limit and the password check run in the backend); stores the hash of a new refresh token"
            urbanpulse.backend.identity -> urbanpulse.nginx "201: access token and refresh token"
            urbanpulse.nginx -> urbanpulse.frontend_mobile "Returns the tokens; the app keeps them in expo-secure-store"
            urbanpulse.frontend_mobile -> urbanpulse.nginx "A call is rejected with 401: POST /api/v1/sessions/refresh"
            urbanpulse.nginx -> urbanpulse.backend.identity "Proxies the request"
            urbanpulse.backend.identity -> urbanpulse.postgres "In one transaction, with the user locked: revokes the presented token and stores a new one. A token already revoked revokes every session of the user (401)"
            urbanpulse.backend.identity -> urbanpulse.nginx "200: a new access token and refresh token"
            urbanpulse.nginx -> urbanpulse.frontend_mobile "Returns the new pair; the app repeats the rejected call"
            autoLayout lr
        }

        dynamic urbanpulse.backend "Dynamic_ReportIncident" {
            title "Reporting an incident from the mobile app"
            description "RF03 and RF26: the report is saved without urban context, so no external source can block it. The enrichment that follows (ADR-0009) is asynchronous, not built yet, and not shown."
            citizen -> urbanpulse.frontend_mobile "Writes a title and description, picks an optional category and confirms the position"
            urbanpulse.frontend_mobile -> urbanpulse.nginx "POST /api/v1/incidents with the access token"
            urbanpulse.nginx -> urbanpulse.backend.incidents "Proxies the request; shared.security validates the token"
            urbanpulse.backend.incidents -> urbanpulse.backend.identity "Loads the reporter"
            urbanpulse.backend.incidents -> urbanpulse.postgres "In one transaction: the incident, its location and the REPORTED status change"
            urbanpulse.backend.incidents -> urbanpulse.nginx "201: the new incident"
            urbanpulse.nginx -> urbanpulse.frontend_mobile "Returns the incident, which appears in My reports"
            autoLayout lr
        }

        # Colours follow the database schema diagram (docs/database.drawio.svg).
        styles {
            element "Person" {
                shape Person
                background "#7b52a6"
                color "#ffffff"
            }
            element "System" {
                background "#2563eb"
                color "#ffffff"
            }
            element "Backend" {
                shape RoundedBox
                background "#2563eb"
                color "#ffffff"
            }
            element "FrontendWeb" {
                shape WebBrowser
                background "#2563eb"
                color "#ffffff"
            }
            element "FrontendMobile" {
                shape MobileDevicePortrait
                background "#2563eb"
                color "#ffffff"
            }
            element "ReverseProxy" {
                shape RoundedBox
                background "#2563eb"
                color "#ffffff"
            }
            element "AdminProcess" {
                shape RoundedBox
                background "#2563eb"
                color "#ffffff"
            }
            element "Database" {
                shape Cylinder
                background "#1e40af"
                color "#ffffff"
            }
            element "Storage" {
                shape Folder
                background "#1e40af"
                color "#ffffff"
            }
            element "Cache" {
                shape Cylinder
                background "#b91c1c"
                color "#ffffff"
            }
            element "ExternalAPI" {
                shape Box
                background "#6b7280"
                color "#ffffff"
            }
            element "Infrastructure Node" {
                shape RoundedBox
                background "#475569"
                color "#ffffff"
            }
            element "Component" {
                shape Component
                background "#2563eb"
                color "#ffffff"
            }
            element "Identity" {
                background "#7b52a6"
            }
            element "Incidents" {
                background "#2563eb"
            }
            element "UrbanContext" {
                background "#3d8f3d"
            }
            element "Geospatial" {
                background "#0f766e"
            }
            element "Notifications" {
                background "#c27803"
            }
            element "Analytics" {
                background "#475569"
            }
            element "Proposed" {
                border dashed
                opacity 80
            }
            element "Future" {
                background "#9ca3af"
                color "#ffffff"
                border dotted
                opacity 70
            }
            relationship "Relationship" {
                color "#6b7280"
                dashed false
            }
            relationship "Async" {
                dashed true
            }
            relationship "Future" {
                style dotted
                color "#9ca3af"
            }
        }
    }
}
