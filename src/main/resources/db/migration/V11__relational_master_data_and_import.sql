CREATE TABLE empresas (
    id CHAR(36) PRIMARY KEY,
    nombre VARCHAR(180) NOT NULL,
    nombre_normalizado VARCHAR(180) NOT NULL,
    ruc VARCHAR(20) NOT NULL DEFAULT '',
    telefono VARCHAR(40) NOT NULL DEFAULT '',
    direccion VARCHAR(500) NOT NULL DEFAULT '',
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_empresas_nombre UNIQUE (nombre_normalizado),
    INDEX idx_empresas_estado (estado, deleted)
);

CREATE TABLE marcas (
    id CHAR(36) PRIMARY KEY,
    empresa_id CHAR(36) NOT NULL,
    nombre VARCHAR(180) NOT NULL,
    nombre_normalizado VARCHAR(180) NOT NULL,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_marcas_empresa FOREIGN KEY (empresa_id) REFERENCES empresas(id),
    CONSTRAINT uq_marcas_empresa_nombre UNIQUE (empresa_id, nombre_normalizado),
    INDEX idx_marcas_empresa (empresa_id, estado, deleted)
);

CREATE TABLE categorias (
    id CHAR(36) PRIMARY KEY,
    categoria_padre_id CHAR(36) NULL,
    nombre VARCHAR(180) NOT NULL,
    nombre_normalizado VARCHAR(180) NOT NULL,
    descripcion VARCHAR(1000) NOT NULL DEFAULT '',
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_categorias_padre FOREIGN KEY (categoria_padre_id) REFERENCES categorias(id),
    CONSTRAINT uq_categorias_padre_nombre UNIQUE (categoria_padre_id, nombre_normalizado),
    INDEX idx_categorias_padre (categoria_padre_id, estado, deleted)
);

CREATE TABLE marca_categorias (
    id CHAR(36) PRIMARY KEY,
    marca_id CHAR(36) NOT NULL,
    categoria_id CHAR(36) NOT NULL,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_marca_categorias_marca FOREIGN KEY (marca_id) REFERENCES marcas(id),
    CONSTRAINT fk_marca_categorias_categoria FOREIGN KEY (categoria_id) REFERENCES categorias(id),
    CONSTRAINT uq_marca_categorias UNIQUE (marca_id, categoria_id),
    INDEX idx_marca_categorias_categoria (categoria_id, estado, deleted)
);

CREATE TABLE unidades_medida (
    id VARCHAR(160) PRIMARY KEY,
    codigo VARCHAR(40) NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    simbolo VARCHAR(30) NOT NULL,
    magnitud VARCHAR(80) NOT NULL,
    factor_a_base DECIMAL(24, 10) NOT NULL DEFAULT 1,
    decimales INT NOT NULL DEFAULT 3,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_unidades_codigo UNIQUE (codigo),
    INDEX idx_unidades_magnitud (magnitud, estado, deleted)
);

CREATE TABLE categoria_atributos (
    id VARCHAR(160) PRIMARY KEY,
    categoria_id CHAR(36) NOT NULL,
    nombre VARCHAR(180) NOT NULL,
    clave VARCHAR(180) NOT NULL,
    ayuda VARCHAR(1000) NULL,
    tipo_dato VARCHAR(40) NOT NULL,
    nivel_captura VARCHAR(30) NOT NULL DEFAULT 'familia',
    requerido_activar BOOLEAN NOT NULL DEFAULT FALSE,
    visible_ficha BOOLEAN NOT NULL DEFAULT TRUE,
    filtrable BOOLEAN NOT NULL DEFAULT FALSE,
    puede_ser_eje BOOLEAN NOT NULL DEFAULT FALSE,
    activo_nuevos BOOLEAN NOT NULL DEFAULT TRUE,
    longitud_maxima INT NULL,
    ejemplo VARCHAR(500) NULL,
    minimo DECIMAL(24, 8) NULL,
    maximo DECIMAL(24, 8) NULL,
    decimales INT NOT NULL DEFAULT 0,
    magnitud VARCHAR(80) NULL,
    maximo_selecciones INT NULL,
    etiqueta_verdadero VARCHAR(120) NULL,
    etiqueta_falso VARCHAR(120) NULL,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_categoria_atributos_categoria FOREIGN KEY (categoria_id) REFERENCES categorias(id),
    CONSTRAINT uq_categoria_atributos_nombre UNIQUE (categoria_id, nombre),
    CONSTRAINT uq_categoria_atributos_clave UNIQUE (categoria_id, clave),
    INDEX idx_categoria_atributos_categoria (categoria_id, estado, orden, deleted)
);

CREATE TABLE categoria_atributo_opciones (
    id VARCHAR(160) PRIMARY KEY,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    etiqueta VARCHAR(180) NOT NULL,
    codigo VARCHAR(100) NOT NULL,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_atributo_opciones_atributo FOREIGN KEY (categoria_atributo_id) REFERENCES categoria_atributos(id),
    CONSTRAINT uq_atributo_opciones_etiqueta UNIQUE (categoria_atributo_id, etiqueta),
    CONSTRAINT uq_atributo_opciones_codigo UNIQUE (categoria_atributo_id, codigo),
    INDEX idx_atributo_opciones_atributo (categoria_atributo_id, estado, orden, deleted)
);

CREATE TABLE categoria_atributo_unidades (
    id VARCHAR(160) PRIMARY KEY,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    unidad_medida_id VARCHAR(160) NOT NULL,
    es_predeterminada BOOLEAN NOT NULL DEFAULT FALSE,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_atributo_unidades_atributo FOREIGN KEY (categoria_atributo_id) REFERENCES categoria_atributos(id),
    CONSTRAINT fk_atributo_unidades_unidad FOREIGN KEY (unidad_medida_id) REFERENCES unidades_medida(id),
    CONSTRAINT uq_atributo_unidades UNIQUE (categoria_atributo_id, unidad_medida_id),
    INDEX idx_atributo_unidades_atributo (categoria_atributo_id, estado, orden, deleted)
);

CREATE TABLE atributos_def (
    id CHAR(36) PRIMARY KEY,
    categoria_id CHAR(36) NOT NULL,
    nombre VARCHAR(180) NOT NULL,
    tipo VARCHAR(60) NOT NULL,
    es_variante BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_atributos_def_categoria FOREIGN KEY (categoria_id) REFERENCES categorias(id),
    INDEX idx_atributos_def_categoria (categoria_id, deleted)
);

CREATE TABLE listas_precios (
    id VARCHAR(160) PRIMARY KEY,
    nombre VARCHAR(180) NOT NULL,
    nombre_normalizado VARCHAR(180) NOT NULL,
    moneda VARCHAR(12) NOT NULL DEFAULT 'PEN',
    incluye_igv BOOLEAN NOT NULL DEFAULT TRUE,
    igv_porcentaje DECIMAL(10, 4) NOT NULL DEFAULT 18,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    origin_device_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_listas_precios_nombre UNIQUE (nombre_normalizado),
    CONSTRAINT chk_listas_precios_moneda CHECK (moneda = 'PEN'),
    INDEX idx_listas_precios_estado (estado, deleted)
);

CREATE TABLE master_imports (
    id CHAR(36) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    warning_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    confirmed_at TIMESTAMP(6) NULL,
    CONSTRAINT uq_master_import_hash UNIQUE (file_hash)
);

CREATE TABLE master_import_rows (
    id CHAR(36) PRIMARY KEY,
    import_id CHAR(36) NOT NULL,
    row_number INT NOT NULL,
    sheet_name VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(160) NOT NULL,
    action VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    messages_json LONGTEXT NOT NULL,
    result_version BIGINT NULL,
    result_sequence BIGINT NULL,
    CONSTRAINT fk_master_import_rows_import FOREIGN KEY (import_id) REFERENCES master_imports(id) ON DELETE CASCADE,
    INDEX idx_master_import_rows_import (import_id, row_number)
);

CREATE OR REPLACE VIEW sync_master_payloads AS
SELECT 'COMPANY' entity_type, id entity_id,
       CAST(JSON_OBJECT('id', id, 'nombre', nombre, 'ruc', ruc, 'telefono', telefono,
            'direccion', direccion, 'estado', estado, 'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR) payload_json
FROM empresas
UNION ALL
SELECT 'BRAND', id,
       CAST(JSON_OBJECT('id', id, 'empresa_id', empresa_id, 'nombre', nombre, 'estado', estado,
            'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR)
FROM marcas
UNION ALL
SELECT 'CATEGORY', id,
       CAST(JSON_OBJECT('id', id, 'categoria_padre_id', categoria_padre_id, 'nombre', nombre,
            'descripcion', descripcion, 'estado', estado, 'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR)
FROM categorias
UNION ALL
SELECT 'BRAND_CATEGORY', id,
       CAST(JSON_OBJECT('id', id, 'marca_id', marca_id, 'categoria_id', categoria_id,
            'estado', estado, 'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR)
FROM marca_categorias
UNION ALL
SELECT 'MEASUREMENT_UNIT', id,
       CAST(JSON_OBJECT('id', id, 'codigo', codigo, 'nombre', nombre, 'simbolo', simbolo,
            'magnitud', magnitud, 'factor_a_base', factor_a_base, 'decimales', decimales, 'estado', estado) AS CHAR)
FROM unidades_medida
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE', id,
       CAST(JSON_OBJECT('id', id, 'categoria_id', categoria_id, 'nombre', nombre, 'clave', clave,
            'ayuda', ayuda, 'tipo_dato', tipo_dato, 'nivel_captura', nivel_captura,
            'requerido_activar', requerido_activar, 'visible_ficha', visible_ficha, 'filtrable', filtrable,
            'puede_ser_eje', puede_ser_eje, 'activo_nuevos', activo_nuevos,
            'longitud_maxima', longitud_maxima, 'ejemplo', ejemplo, 'minimo', minimo, 'maximo', maximo,
            'decimales', decimales, 'magnitud', magnitud, 'maximo_selecciones', maximo_selecciones,
            'etiqueta_verdadero', etiqueta_verdadero, 'etiqueta_falso', etiqueta_falso,
            'orden', orden, 'estado', estado, 'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR)
FROM categoria_atributos
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE_OPTION', id,
       CAST(JSON_OBJECT('id', id, 'categoria_atributo_id', categoria_atributo_id, 'etiqueta', etiqueta,
            'codigo', codigo, 'orden', orden, 'estado', estado) AS CHAR)
FROM categoria_atributo_opciones
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE_UNIT', id,
       CAST(JSON_OBJECT('id', id, 'categoria_atributo_id', categoria_atributo_id,
            'unidad_medida_id', unidad_medida_id, 'es_predeterminada', es_predeterminada,
            'orden', orden, 'estado', estado) AS CHAR)
FROM categoria_atributo_unidades
UNION ALL
SELECT 'LEGACY_ATTRIBUTE_DEFINITION', id,
       CAST(JSON_OBJECT('id', id, 'categoria_id', categoria_id, 'nombre', nombre,
            'tipo', tipo, 'es_variante', es_variante) AS CHAR)
FROM atributos_def
UNION ALL
SELECT 'PRICE_LIST', id,
       CAST(JSON_OBJECT('id', id, 'nombre', nombre, 'moneda', moneda, 'incluye_igv', incluye_igv,
            'igv_porcentaje', igv_porcentaje, 'estado', estado, 'actualizado_en', CAST(updated_at AS CHAR)) AS CHAR)
FROM listas_precios;

INSERT INTO listas_precios(
    id, nombre, nombre_normalizado, moneda, incluye_igv, igv_porcentaje, estado,
    version, last_sequence, deleted, created_at, updated_at
) VALUES (
    'price-list-general', 'General', 'general', 'PEN', TRUE, 18, TRUE,
    0, 0, FALSE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);
