workspace "UrbanPulse" "Modelo C4" {

    !identifiers hierarchical

    # Los enlaces ADR apuntan a boilerplate; cambiadlos a main cuando se fusione.
    model {
        ### Actores del Sistema
        ciudadano = person "Ciudadano" {
            tags "Usuario"
        }

        personal_municipal = person "Personal Municipal" {
            tags "PersonalMunicipal"
        }

        analista = person "Responsables y analistas" {
            tags "Analista"
        }

        admin = person "Administrador" {
            tags "Administrador"
        }

        tecnico = person "Técnico" {
            tags "Tecnico"
        }

        ### System Context - UrbanPulse
        sistema = softwareSystem "UrbanPulse" {
            tags "System"

            ### 1. CONTAINER: FRONTEND WEB (ADR-0006, ADR-0007)
            frontend_web = container "Frontend Web" "SPA para operadores y analistas. Renderiza mapas, listas y dashboards." "React + Vite" {
                tags "FrontendWeb"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0006-frontend-web-technology.md

                web_router = component "Enrutador y Vistas" "React Router para navegación y filtros manejados por URL (área, fecha, estado)." "React Router"
                web_ui = component "Componentes UI y Mapa" "Renderizado de listas densas y mapas interactivos con react-leaflet." "React / Leaflet"
                web_state = component "Gestor de Estado (Server State)" "Caché de cliente, reintentos e invalidación de incidencias." "React Query"
                web_shared = component "Paquetes Compartidos (Monorepo)" "Contratos tipados, cliente HTTP y utilidades de presentación compartidas con la app móvil; la lógica de dominio vive en el backend." "TypeScript" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
                }

                web_router -> web_ui "Renderiza"
                web_ui -> web_state "Solicita datos"
                web_state -> web_shared "Delega peticiones HTTP"
            }

            ### 2. CONTAINER: FRONTEND MÓVIL (ADR-0005, ADR-0007)
            frontend_movil = container "Frontend Móvil" "App móvil para reportar incidencias y gestión en campo." "React Native + Expo" {
                tags "FrontendMobile"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0005-mobile-application-technology.md

                mobile_ui = component "Vistas Móviles y Mapa" "Formularios de reporte e interacción con mapas." "React Native Maps"
                mobile_native = component "Módulos Nativos (Expo)" "Acceso a cámara, GPS, sistema de ficheros y almacenamiento seguro de tokens." "Expo Modules"
                mobile_state = component "Gestor de Estado y Offline" "Caché de estado de servidor y persistencia local de borradores offline (AsyncStorage)." "React Query + AsyncStorage"
                mobile_shared = component "Paquetes Compartidos (Monorepo)" "Contratos tipados, cliente HTTP y utilidades de presentación compartidas con la web; la lógica de dominio vive en el backend." "TypeScript" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0007-client-application-architecture.md
                }

                mobile_ui -> mobile_native "Usa hardware del dispositivo"
                mobile_ui -> mobile_state "Solicita datos y guarda borradores"
                mobile_state -> mobile_shared "Delega peticiones HTTP"
            }

            ### 3. CONTAINER: BACKEND API (ADR-0008, ADR-0010)
            backend = container "Backend API" "Expone API REST, gestiona transacciones y procesos asíncronos." "Spring Boot (Java)" {
                tags "Backend"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0008-backend.md

                controllers = component "API REST Controllers" "Puntos de entrada de la API REST (OpenAPI/JSON)." "Spring Web"
                identity = component "Identity Module" "Usuarios, roles y gestión de JWT de corta duración y refresh tokens hasheados." "Spring Security" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0004-authentication-strategy.md
                }
                incidents = component "Incidents Module" "Ciclo de vida, asignaciones, comentarios y auditoría transaccional." "Spring Service"
                geospatial = component "Geospatial Module" "Ubicaciones, consultas espaciales, resolución de distritos y activos." "Spring Data / PostGIS"
                externaldata = component "External Data Module" "Ingesta asíncrona de datos de OpenData y OSM; normaliza observaciones y conserva el payload crudo para auditoría y procedencia." "Spring Service" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
                }
                notifications = component "Notifications Module" "Eventos y envío de notificaciones." "Spring Service"
                analytics = component "Analytics Module" "Estadísticas y generación de informes." "Spring Service"
                intelligence = component "Intelligence Module" "Clasificación y priorización asistida, resúmenes, recomendaciones y RAG con citas; registra modelo, confianza y aceptación humana." "Spring AI / LLM" {
                    tags "Future"
                }

                controllers -> identity "Autentica peticiones"
                controllers -> incidents "Gestiona incidencias"
                controllers -> analytics "Solicita reportes"
                controllers -> intelligence "Solicita asistencia"
                incidents -> geospatial "Usa consultas espaciales"
                incidents -> notifications "Dispara eventos"
                geospatial -> externaldata "Solicita contexto"
            }

            ### 4. CONTAINER: DATABASE (ADR-0001, ADR-0003)
            postgres = container "PostgreSQL" "Almacena datos relacionales con integridad referencial, JSONB y geometría espacial." "PostgreSQL 18 + PostGIS 3.6" {
                tags "Database"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0001-database.md

                db_relational = component "Esquema Relacional" "Tablas con claves foráneas, constraints e integridad referencial (Incidencias, Usuarios, metadatos de evidencias, refresh tokens hasheados y observaciones externas normalizadas)." "Tablas SQL"
                db_postgis = component "Motor Espacial (PostGIS)" "Tipos geography(Point, 4326), índices espaciales y operadores (distancia, contención)." "Extensión PostGIS" {
                    url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0003-geospatial-data-management.md
                }
                db_jsonb = component "Almacenamiento Documental" "Payload crudo de OpenStreetMap y otras fuentes para auditoría y procedencia; no es el modelo de consulta del dominio." "JSONB"
            }

            ### 5. CONTAINER: CACHE (ADR-0012, PROPOSED)
            redis = container "Redis Cache" "Almacena datos efímeros con TTL, contadores de rate limit y respuestas externas." "Redis" {
                tags "Cache,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0012-cache-redis.md
            }

            ### 6. CONTAINER: REVERSE PROXY (ADR-0014, PROPOSED)
            nginx = container "Reverse Proxy / Web Server" "Punto de entrada HTTP en producción: sirve el build web y hace proxy de /api hacia el backend." "nginx" {
                tags "ReverseProxy,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0014-reverse-proxy.md
            }

            ### 7. CONTAINER: EVIDENCE STORAGE (ADR-0013, PROPOSED)
            evidence_storage = container "Evidence Storage" "Almacena binarios de evidencias fuera de PostgreSQL; la base de datos solo guarda storage_key y metadatos." "Volume / S3-compatible" {
                tags "Storage,Proposed"
                url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0013-evidence-storage.md
            }

            # Relaciones entre Contenedores y Componentes
            frontend_web.web_shared -> backend.controllers "HTTP/REST (JSON)"
            frontend_movil.mobile_shared -> backend.controllers "HTTP/REST (JSON)"

            backend.identity -> postgres.db_relational "Almacena y verifica Refresh Tokens hasheados (SHA-256)"
            backend.incidents -> postgres.db_relational "Transacciones ACID (lectura/escritura)"
            backend.geospatial -> postgres.db_postgis "Consultas y filtros espaciales (SQL)"
            backend.externaldata -> postgres.db_relational "Persiste observaciones normalizadas (fuente, variable, valor, unidad, fechas y calidad)"
            backend.externaldata -> postgres.db_jsonb "Guarda respuestas crudas en JSONB para auditoría y procedencia"
            backend.intelligence -> postgres.db_relational "Predicciones, documentos y citas"

            backend.externaldata -> redis "Lee/escribe caché con TTL"
            backend.identity -> redis "Gestiona rate-limits de login"

            # Relaciones de nivel de contenedor
            frontend_web -> nginx "Peticiones /api"
            frontend_movil -> nginx "HTTP/REST (JSON)"
            nginx -> backend "Proxy /api"
            backend -> postgres "Lee y escribe"
            backend -> redis "Caché y rate limiting"
            backend -> evidence_storage "Binarios de evidencias"

            # Relaciones de actores con contenedores
            ciudadano -> frontend_movil "Reporta incidencias y adjunta evidencias"
            personal_municipal -> frontend_web "Filtra y revisa incidencias"
            analista -> frontend_web "Consulta dashboards"
            admin -> frontend_web "Gestiona cuentas y roles"
            tecnico -> frontend_web "Consulta documentación en despacho"
            tecnico -> frontend_movil "Actualiza trabajos en campo"
        }

        ### Entidades externas / APIs
        openstreet = softwareSystem "OpenStreetMap" "Proveedor de datos cartográficos, geocodificación (Nominatim) y activos (Overpass)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0002-open-street-map.md
        }

        opendata = softwareSystem "Open Data Málaga" "Fuente principal de distritos y equipamiento urbano (datos.malaga.eu)." {
            tags "ExternalAPI"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
        }

        llm = softwareSystem "LLM Provider" "Modelo externo para clasificación, resumen y RAG (a decidir)." {
            tags "ExternalAPI,Future"
        }

        weather_traffic = softwareSystem "Weather & Traffic Sources" "Fuentes de clima y tráfico urbano (AEMET / tráfico municipal, a concretar)." {
            tags "ExternalAPI,Future"
            url https://github.com/Dalibex/urban-incidence-manager/blob/boilerplate/docs/adr/0009-urban-data-source.md
        }

        # Relaciones de nivel de sistema (contexto)
        ciudadano -> sistema "Reporta incidencias y consulta su estado"
        personal_municipal -> sistema "Valida, prioriza y asigna incidencias"
        analista -> sistema "Consulta indicadores y patrones"
        admin -> sistema "Gestiona usuarios y catálogos"
        tecnico -> sistema "Actualiza el trabajo de campo"

        sistema -> openstreet "Cartografía y geocodificación"
        sistema -> opendata "Datos urbanos autoritativos"
        sistema -> weather_traffic "Clima y tráfico"
        sistema -> llm "Asistencia inteligente"

        # Relaciones de contenedores con sistemas externos
        sistema.frontend_web -> openstreet "Carga OSM Tiles"
        sistema.frontend_movil -> openstreet "Carga OSM Tiles"
        sistema.backend -> openstreet "Geocodificación y activos"
        sistema.backend -> opendata "Datos urbanos autoritativos"
        sistema.backend -> weather_traffic "Clima y tráfico"
        sistema.backend -> llm "Asistencia inteligente"

        # Relaciones de componentes con sistemas externos
        sistema.backend.externaldata -> opendata "Consulta datos autoritativos"
        sistema.backend.externaldata -> openstreet "Consulta geocodificación y fallback de activos"
        sistema.backend.externaldata -> weather_traffic "Consulta clima y tráfico"
        sistema.backend.intelligence -> llm "Clasificación, resumen y RAG"
        sistema.frontend_web.web_ui -> openstreet "Carga OSM Tiles"
        sistema.frontend_movil.mobile_ui -> openstreet "Carga OSM Tiles"

        ### Relaciones de los actores con el sistema
        ciudadano -> sistema.frontend_movil.mobile_ui "Reporta incidencias y adjunta evidencias"
        personal_municipal -> sistema.frontend_web.web_router "Filtra y revisa listas densas de incidencias"
        personal_municipal -> sistema.frontend_web.web_ui "Inspecciona evidencias en mapas"
        analista -> sistema.frontend_web.web_router "Visualiza dashboards estadísticos"
        admin -> sistema.frontend_web.web_router "Gestiona cuentas y roles"
        tecnico -> sistema.frontend_web.web_router "Consulta documentación en despacho"
        tecnico -> sistema.frontend_movil.mobile_ui "Actualiza progreso de trabajos en campo"
    }

    views {
        systemContext sistema "SystemContext" {
            include *
            autoLayout lr
        }

        container sistema "Containers" {
            include *
            autoLayout lr
        }

        component sistema.backend "Backend_Components" {
            include *
            autoLayout tb
        }

        component sistema.frontend_web "FrontendWeb_Components" {
            include *
            autoLayout tb
        }

        component sistema.frontend_movil "FrontendMovil_Components" {
            include *
            autoLayout tb
        }

        component sistema.postgres "Database_Components" {
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