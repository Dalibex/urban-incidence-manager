workspace "UrbanPulse" "C4 Model" {

    !identifiers hierarchical

    # ADR links point to boilerplate; change them to main after merging.
    model {
        ### System Actors
        citizen = person "Citizen" {
            tags "Citizen"
        }

        municipal_staff = person "Municipal Staff" {
            tags "MunicipalStaff"
        }

        analyst = person "Managers and Analysts" {
            tags "Analyst"
        }

        admin = person "Administrator" {
            tags "Administrator"
        }

        technician = person "Technician" {
            tags "Technician"
        }

        ### System Context - UrbanPulse
        urbanpulse = softwareSystem "UrbanPulse" {
            tags "System"

            ### 1. CONTAINER: WEB FRONTEND (ADR-0006, ADR-0007)
            frontend_web = container "Web Frontend" "SPA for operators and analysts. Renders maps, lists, and dashboards." "React + Vite" {
                tags "FrontendWeb"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0006-frontend-web-technology.md

                web_router = component "Router and Views" "React Router for navigation and URL-driven filters (area, date, status)." "React Router"
                web_ui = component "UI Components and Map" "Renders dense lists and interactive maps with react-leaflet." "React / Leaflet"
                web_state = component "State Manager (Server State)" "Client-side cache, retries, and incident invalidation." "React Query"
                web_shared = component "Shared Packages (Monorepo)" "Typed contracts, HTTP client, and presentation utilities shared with the mobile app; domain logic lives in the backend." "TypeScript" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
                }

                web_router -> web_ui "Renders"
                web_ui -> web_state "Requests data"
                web_state -> web_shared "Delegates HTTP requests"
            }

            ### 2. CONTAINER: MOBILE FRONTEND (ADR-0005, ADR-0007)
            frontend_mobile = container "Mobile Frontend" "Mobile app for reporting incidents and field management." "React Native + Expo" {
                tags "FrontendMobile"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0005-mobile-application-technology.md

                mobile_ui = component "Mobile Views and Map" "Report forms and map interaction." "React Native Maps"
                mobile_native = component "Native Modules (Expo)" "Access to camera, GPS, file system, and secure token storage." "Expo Modules"
                mobile_state = component "State and Offline Manager" "Server state cache and local persistence of offline drafts (AsyncStorage)." "React Query + AsyncStorage"
                mobile_shared = component "Shared Packages (Monorepo)" "Typed contracts, HTTP client, and presentation utilities shared with the web app; domain logic lives in the backend." "TypeScript" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
                }

                mobile_ui -> mobile_native "Uses device hardware"
                mobile_ui -> mobile_state "Requests data and saves drafts"
                mobile_state -> mobile_shared "Delegates HTTP requests"
            }

            ### 3. CONTAINER: BACKEND API (ADR-0008, ADR-0010)
            backend = container "Backend API" "Exposes a REST API and manages transactions and asynchronous processes." "Spring Boot (Java)" {
                tags "Backend"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0008-backend.md

                controllers = component "REST API Controllers" "REST API entry points (OpenAPI/JSON)." "Spring Web"
                identity = component "Identity Module" "Users, roles, and management of short-lived JWTs and hashed refresh tokens." "Spring Security" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0004-authentication-strategy.md
                }
                incidents = component "Incidents Module" "Lifecycle, assignments, comments, and transactional audit." "Spring Service"
                geospatial = component "Geospatial Module" "Locations, spatial queries, district and asset resolution." "Spring Data / PostGIS"
                externaldata = component "External Data Module" "Asynchronous ingestion of OpenData and OSM data; normalizes observations and preserves the raw payload for audit and provenance." "Spring Service" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
                }
                notifications = component "Notifications Module" "Events and notification delivery." "Spring Service"
                analytics = component "Analytics Module" "Statistics and report generation." "Spring Service"
                intelligence = component "Intelligence Module" "Assisted classification and prioritization, summaries, recommendations, and RAG with citations; records model, confidence, and human acceptance." "Spring AI / LLM" {
                    tags "Future"
                }

                controllers -> identity "Authenticates requests"
                controllers -> incidents "Manages incidents"
                controllers -> analytics "Requests reports"
                controllers -> intelligence "Requests assistance"
                incidents -> geospatial "Uses spatial queries"
                incidents -> notifications "Triggers events"
                geospatial -> externaldata "Requests context"
            }

            ### 4. CONTAINER: DATABASE (ADR-0001, ADR-0003)
            postgres = container "PostgreSQL" "Stores relational data with referential integrity, JSONB, and spatial geometry." "PostgreSQL 18 + PostGIS 3.6" {
                tags "Database"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0001-database.md

                db_relational = component "Relational Schema" "Tables with foreign keys, constraints, and referential integrity (Incidents, Users, evidence metadata, hashed refresh tokens, and normalized external observations)." "SQL tables"
                db_postgis = component "Spatial Engine (PostGIS)" "Supports geography(Point, 4326) types, spatial indexes, and operators (distance, containment)." "PostGIS extension" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0003-geospatial-data-management.md
                }
                db_jsonb = component "Document Storage" "Raw payload from OpenStreetMap and other sources for audit and provenance; it is not the domain query model." "JSONB"
            }

            ### 5. CONTAINER: CACHE (ADR-0012, PROPOSED)
            redis = container "Redis Cache" "Stores ephemeral data with TTL, rate-limit counters, and external responses." "Redis" {
                tags "Cache,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0012-cache-redis.md
            }

            ### 6. CONTAINER: REVERSE PROXY (ADR-0014, PROPOSED)
            nginx = container "Reverse Proxy / Web Server" "Production HTTP entry point: serves the web build and proxies /api to the backend." "nginx" {
                tags "ReverseProxy,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0014-reverse-proxy.md
            }

            ### 7. CONTAINER: EVIDENCE STORAGE (ADR-0013, PROPOSED)
            evidence_storage = container "Evidence Storage" "Stores evidence binaries outside PostgreSQL; the database only stores storage_key and metadata." "Volume / S3-compatible" {
                tags "Storage,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0013-evidence-storage.md
            }

            # Relationships between containers and components
            frontend_web.web_shared -> backend.controllers "HTTP/REST (JSON)"
            frontend_mobile.mobile_shared -> backend.controllers "HTTP/REST (JSON)"

            backend.identity -> postgres.db_relational "Stores and verifies hashed refresh tokens (SHA-256)"
            backend.incidents -> postgres.db_relational "ACID transactions (read/write)"
            backend.geospatial -> postgres.db_postgis "Spatial queries and filters (SQL)"
            backend.externaldata -> postgres.db_relational "Persists normalized observations (source, variable, value, unit, dates, and quality)"
            backend.externaldata -> postgres.db_jsonb "Stores raw responses in JSONB for audit and provenance"
            backend.intelligence -> postgres.db_relational "Predictions, documents, and citations"

            backend.externaldata -> redis "Reads/writes TTL cache"
            backend.identity -> redis "Manages login rate limits"

            # Container-level relationships
            frontend_web -> nginx "/api requests"
            frontend_mobile -> nginx "HTTP/REST (JSON)"
            nginx -> backend "Proxies /api"
            backend -> postgres "Reads and writes"
            backend -> redis "Cache and rate limiting"
            backend -> evidence_storage "Evidence binaries"

            # Actor-to-container relationships
            citizen -> frontend_mobile "Reports incidents and attaches evidence"
            municipal_staff -> frontend_web "Filters and reviews incidents"
            analyst -> frontend_web "Views dashboards"
            admin -> frontend_web "Manages accounts and roles"
            technician -> frontend_web "Consults documentation at the office"
            technician -> frontend_mobile "Updates field work"
        }

        ### External entities / APIs
        openstreet = softwareSystem "OpenStreetMap" "Provider of cartographic data, geocoding (Nominatim), and assets (Overpass)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0002-open-street-map.md
        }

        opendata = softwareSystem "Open Data Málaga" "Primary source of districts and urban facilities (datos.malaga.eu)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
        }

        llm = softwareSystem "LLM Provider" "External model for classification, summarization, and RAG (to be decided)." {
            tags "ExternalAPI,Future"
        }

        weather_traffic = softwareSystem "Weather & Traffic Sources" "Weather and urban traffic sources (AEMET / municipal traffic, to be defined)." {
            tags "ExternalAPI,Future"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
        }

        # System-level relationships (context)
        citizen -> urbanpulse "Reports incidents and checks their status"
        municipal_staff -> urbanpulse "Validates, prioritizes, and assigns incidents"
        analyst -> urbanpulse "Views indicators and patterns"
        admin -> urbanpulse "Manages users and catalogs"
        technician -> urbanpulse "Updates field work"

        urbanpulse -> openstreet "Mapping and geocoding"
        urbanpulse -> opendata "Authoritative urban data"
        urbanpulse -> weather_traffic "Weather and traffic"
        urbanpulse -> llm "Intelligent assistance"

        # Container relationships with external systems
        urbanpulse.frontend_web -> openstreet "Loads OSM tiles"
        urbanpulse.frontend_mobile -> openstreet "Loads OSM tiles"
        urbanpulse.backend -> openstreet "Geocoding and assets"
        urbanpulse.backend -> opendata "Authoritative urban data"
        urbanpulse.backend -> weather_traffic "Weather and traffic"
        urbanpulse.backend -> llm "Intelligent assistance"

        # Component relationships with external systems
        urbanpulse.backend.externaldata -> opendata "Queries authoritative data"
        urbanpulse.backend.externaldata -> openstreet "Queries geocoding and asset fallback"
        urbanpulse.backend.externaldata -> weather_traffic "Queries weather and traffic"
        urbanpulse.backend.intelligence -> llm "Classification, summarization, and RAG"
        urbanpulse.frontend_web.web_ui -> openstreet "Loads OSM tiles"
        urbanpulse.frontend_mobile.mobile_ui -> openstreet "Loads OSM tiles"

        ### Actor relationships with the system
        citizen -> urbanpulse.frontend_mobile.mobile_ui "Reports incidents and attaches evidence"
        municipal_staff -> urbanpulse.frontend_web.web_router "Filters and reviews dense incident lists"
        municipal_staff -> urbanpulse.frontend_web.web_ui "Inspects evidence on maps"
        analyst -> urbanpulse.frontend_web.web_router "Views statistical dashboards"
        admin -> urbanpulse.frontend_web.web_router "Manages accounts and roles"
        technician -> urbanpulse.frontend_web.web_router "Consults documentation at the office"
        technician -> urbanpulse.frontend_mobile.mobile_ui "Updates field work progress"
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

        styles {
            element "Person" {
                shape Person
                background "#08427b"
                color "#ffffff"
            }
            element "System" {
                background "#1168bd"
                color "#ffffff"
            }
            element "Backend" {
                shape RoundedBox
                background "#438dd5"
                color "#ffffff"
            }
            element "FrontendWeb" {
                shape WebBrowser
                background "#438dd5"
                color "#ffffff"
            }
            element "FrontendMobile" {
                shape MobileDevicePortrait
                background "#438dd5"
                color "#ffffff"
            }
            element "Database" {
                shape Cylinder
                background "#2a7db7"
                color "#ffffff"
            }
            element "Cache" {
                shape Cylinder
                background "#ff4d4d"
                color "#ffffff"
            }
            element "ExternalAPI" {
                shape Box
                background "#999999"
                color "#ffffff"
            }
            element "ReverseProxy" {
                shape RoundedBox
                background "#438dd5"
                color "#ffffff"
            }
            element "Storage" {
                shape Cylinder
                background "#2a7db7"
                color "#ffffff"
            }
            element "Component" {
                shape Component
                background "#85bbf0"
                color "#000000"
            }
            element "Proposed" {
                background "#cccccc"
                color "#000000"
                stroke "#999999"
                border dashed
            }
            element "Future" {
                background "#eeeeee"
                color "#666666"
                stroke "#bbbbbb"
                border dotted
            }
        }
    }
}
