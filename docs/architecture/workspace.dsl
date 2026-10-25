workspace "UrbanPulse" "C4 Model" {

    !identifiers hierarchical
    # Every relationship is written explicitly at the level where it is shown, so Structurizr
    # does not add implied arrows (duplicates, or web -> backend skipping nginx).
    !impliedRelationships false

    # ADR links point to boilerplate; change them to main after merging.
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
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0006-frontend-web-technology.md

                web_router = component "Router and Views" "React Router for navigation and URL-driven filters (area, date, status)." "React Router"
                web_ui = component "UI Components and Map" "Dense incident lists, management forms and the incident map over the self-hosted base map." "React / MapLibre GL JS (react-map-gl)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0016-self-hosted-osm-base-map.md
                }
                web_state = component "State Manager (Server State)" "Client-side cache, retries, and invalidation of incidents and evidences." "React Query"
                web_shared = component "Shared Packages (Monorepo)" "packages/domain (entities, enums, business rules, validation schemas), packages/api-client (typed HTTP client generated from OpenAPI, token refresh) and packages/ui, shared with the mobile app." "TypeScript (pnpm workspaces)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
                }

                web_router -> web_ui "Renders"
                web_ui -> web_state "Requests data"
                web_state -> web_shared "Delegates HTTP requests"
            }

            ### 2. CONTAINER: MOBILE FRONTEND (ADR-0005, ADR-0007, ADR-0016)
            frontend_mobile = container "Mobile Frontend" "App for citizens to report and follow incidents and for technicians' field work." "React Native + Expo (EAS)" {
                tags "FrontendMobile"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0005-mobile-application-technology.md

                mobile_ui = component "Mobile Views and Map" "Report forms, navigation and map interaction over the self-hosted base map (requires an Expo development build)." "React Navigation / MapLibre React Native" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0016-self-hosted-osm-base-map.md
                }
                mobile_native = component "Native Modules (Expo)" "Access to camera, GPS, file system, and secure token storage." "Expo Modules"
                mobile_state = component "State and Offline Manager" "Server state cache and local persistence of offline drafts (AsyncStorage)." "React Query + AsyncStorage"
                mobile_shared = component "Shared Packages (Monorepo)" "The same packages/domain, packages/api-client and packages/ui as the web app." "TypeScript (pnpm workspaces)" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
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
            backend = container "Backend API" "Contract-first REST API (OpenAPI) built as a modular monolith; manages transactions and scheduled background jobs." "Spring Boot (Java)" {
                tags "Backend"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0008-backend.md

                identity = component "identity" "Users, roles and permissions; sign-up, login, short-lived JWTs and rotating hashed refresh tokens; Spring Security configuration applied to every request; audit log." "Spring Security / Spring Data JPA" {
                    tags "Identity"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0004-authentication-strategy.md
                }
                incidents = component "incidents" "The incident as the central entity: reporting, location, categories, priority, lifecycle with allowed transitions, audited status history, assignments, comments and evidence metadata." "Spring Web / Spring Data JPA" {
                    tags "Incidents"
                }
                urbancontext = component "urbancontext" "External data: adapters for Open Data Málaga, OpenStreetMap, Open-Meteo and TomTom; loading of reference data, geocoding and asynchronous enrichment of incidents with timeouts and graceful degradation." "Spring Service / Spring Data JPA" {
                    tags "UrbanContext"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
                }
                geospatial = component "geospatial" "Location and territory: districts, neighbourhoods, urban assets and spatial queries over local data." "Spring Service / Spring Data JPA / PostGIS" {
                    tags "Geospatial"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0003-geospatial-data-management.md
                }
                notifications = component "notifications" "Notifications created on incident status changes and their delivery status; email (Brevo) and SMS (Twilio) adapters behind one sender interface per channel." "Spring Service / Spring Data JPA" {
                    tags "Notifications"
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0019-notification-delivery-providers.md
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
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0001-database.md

                db_relational = component "Relational Schema" "Table groups created by Flyway: identity (users, refresh_token, audit_log), incidents (incident, location, category, department, assignment, status_change, comment, attachment), urbancontext (urban_context, external_observation, data_source_config; also urban_asset and incident_asset, used by geospatial), notifications (notification)." "SQL tables" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0014-database-migrations-flyway.md
                }
                db_postgis = component "Spatial Engine (PostGIS)" "geography(Point, 4326) in location.coordinates and urban_asset.geometry with GiST indexes; distance and containment operators." "PostGIS extension" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0003-geospatial-data-management.md
                }
                db_jsonb = component "JSONB Columns" "Semi-structured fields: audit_log.details, status_change.associated_data and urban_asset.metadata." "JSONB"
            }

            ### 5. CONTAINER: CACHE (ADR-0012, ACCEPTED)
            redis = container "Redis Cache" "Cache-aside with TTL on every entry: external responses, resolved geodata per zone, dashboard aggregates and rate-limit counters. Safe to lose." "Redis" {
                tags "Cache"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0012-cache-redis.md
            }

            ### 6. CONTAINER: REVERSE PROXY (ADR-0017, PROPOSED)
            nginx = container "Reverse Proxy / Web Server" "Single published entry point in production: serves the web build and the base map tiles (/tiles/) and proxies /api to the backend." "nginx" {
                tags "ReverseProxy,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0017-reverse-proxy.md
            }

            ### 7. CONTAINER: EVIDENCE STORAGE (ADR-0013, PROPOSED)
            evidence_storage = container "Evidence Storage" "Evidence binaries outside PostgreSQL in the evidence_data volume mounted in the backend, accessed only through StorageService with server-generated keys; S3-compatible storage later." "Docker named volume (LocalFsStorage)" {
                tags "Storage,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0013-evidence-storage.md
            }

            ### 8. CONTAINER: BASE MAP TILES BUILDER (ADR-0016, ACCEPTED)
            tiles_builder = container "Tiles Builder" "One-off admin process run every 14 days: downloads the Andalucía OSM extract, clips it to Málaga and builds malaga.pmtiles (zoom 0-14)." "Planetiler (Docker, compose profile tiles)" {
                tags "AdminProcess"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0016-self-hosted-osm-base-map.md
            }
        }

        ### External entities / APIs
        openstreet = softwareSystem "OpenStreetMap" "Geocoding and reverse geocoding (Nominatim) and map features and urban assets (Overpass). Called only by the backend." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0002-open-street-map.md
        }

        geofabrik = softwareSystem "Geofabrik" "Regional OpenStreetMap extracts (Andalucía, .osm.pbf) used to build the base map." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0016-self-hosted-osm-base-map.md
        }

        opendata = softwareSystem "Open Data Málaga" "Primary source of districts, neighborhoods and urban facilities (datos.malaga.eu)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
        }

        openmeteo = softwareSystem "Open-Meteo" "Current weather by coordinates (WMO weather code, wind, precipitation). No API key." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0018-weather-and-traffic-providers.md
        }

        tomtom = softwareSystem "TomTom Traffic" "Traffic Flow Segment Data: current and free-flow speed of the road nearest to a point." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0018-weather-and-traffic-providers.md
        }

        brevo = softwareSystem "Brevo" "Transactional email API (EU-based provider)." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0019-notification-delivery-providers.md
        }

        twilio = softwareSystem "Twilio" "SMS API (Programmable Messaging)." {
            tags "ExternalAPI,Proposed"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0019-notification-delivery-providers.md
        }

        llm = softwareSystem "LLM Provider" "External model for classification, summarization, and RAG (to be decided; no module yet)." {
            tags "ExternalAPI,Future"
        }

        ### Level 1: system context
        citizen -> urbanpulse "Reports incidents and checks their status"
        operator -> urbanpulse "Validates, prioritizes, and assigns incidents"
        analyst -> urbanpulse "Views indicators and patterns"
        admin -> urbanpulse "Manages users and catalogs"
        technician -> urbanpulse "Updates field work"
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
        technician -> urbanpulse.frontend_web "Consults documentation at the office"
        technician -> urbanpulse.frontend_mobile "Updates field work"

        urbanpulse.frontend_web -> urbanpulse.nginx "Loads the SPA, calls /api and loads the base map (/tiles/)" "HTTPS"
        urbanpulse.frontend_mobile -> urbanpulse.nginx "Calls /api and loads the base map (/tiles/)" "HTTPS"
        urbanpulse.nginx -> urbanpulse.backend "Proxies /api" "HTTP (internal network)"
        urbanpulse.tiles_builder -> geofabrik "Downloads the Andalucía extract" "HTTPS"
        urbanpulse.tiles_builder -> urbanpulse.nginx "Publishes malaga.pmtiles to the tiles volume" "Docker named volume"
        urbanpulse.backend -> urbanpulse.postgres "Reads and writes; applies Flyway migrations on startup" "JDBC"
        urbanpulse.backend -> urbanpulse.redis "Cache-aside with TTL and rate-limit counters"
        urbanpulse.backend -> urbanpulse.evidence_storage "Stores and reads evidence binaries"
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
        urbanpulse.backend.identity -> urbanpulse.redis "Login rate-limit counters"
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
        technician -> urbanpulse.frontend_web.web_router "Consults documentation at the office"
        urbanpulse.frontend_web.web_shared -> urbanpulse.nginx "Calls /api" "HTTPS/JSON"
        urbanpulse.frontend_web.web_ui -> urbanpulse.nginx "Loads base map tiles, style, fonts and sprites (/tiles/)" "HTTPS (PMTiles)"

        citizen -> urbanpulse.frontend_mobile.mobile_ui "Reports incidents and attaches evidence"
        technician -> urbanpulse.frontend_mobile.mobile_ui "Updates field work progress"
        urbanpulse.frontend_mobile.mobile_shared -> urbanpulse.nginx "Calls /api" "HTTPS/JSON"
        urbanpulse.frontend_mobile.mobile_ui -> urbanpulse.nginx "Loads base map tiles (pmtiles://)" "HTTPS (PMTiles)"
    }

    views {
        systemContext urbanpulse "SystemContext" {
            include *
            autoLayout lr
        }

        container urbanpulse "Containers" {
            include *
            autoLayout lr
        }

        component urbanpulse.backend "Backend_Components" {
            include *
            autoLayout tb
        }

        component urbanpulse.frontend_web "FrontendWeb_Components" {
            include *
            autoLayout tb
        }

        component urbanpulse.frontend_mobile "FrontendMobile_Components" {
            include *
            autoLayout tb
        }

        component urbanpulse.postgres "Database_Components" {
            include *
            autoLayout tb
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
