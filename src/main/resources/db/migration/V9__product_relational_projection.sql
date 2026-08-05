CREATE TABLE producto_variantes_catalogo (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    sku VARCHAR(160) NOT NULL,
    codigo_proveedor VARCHAR(160) NOT NULL DEFAULT '',
    nombre_corto VARCHAR(250) NOT NULL DEFAULT '',
    estado VARCHAR(20) NOT NULL,
    atributos_json LONGTEXT NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_variante_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT uq_producto_variante_sku UNIQUE (producto_id, sku),
    INDEX idx_producto_variante_producto (producto_id),
    INDEX idx_producto_variante_sku (sku)
);

CREATE TABLE producto_familia_ejes (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    orden INT NOT NULL DEFAULT 0,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_eje_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT uq_producto_eje_atributo UNIQUE (producto_id, categoria_atributo_id),
    INDEX idx_producto_eje_producto (producto_id)
);

CREATE TABLE producto_atributos (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    variante_id VARCHAR(160) NULL,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    valor_texto VARCHAR(2000) NULL,
    valor_numero DECIMAL(24, 8) NULL,
    valor_booleano BOOLEAN NULL,
    valor_fecha VARCHAR(80) NULL,
    valor_normalizado VARCHAR(500) NULL,
    valor_maximo DECIMAL(24, 8) NULL,
    unidad_medida_id VARCHAR(160) NULL,
    categoria_atributo_unidad_id VARCHAR(160) NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_atributo_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    INDEX idx_producto_atributo_producto (producto_id),
    INDEX idx_producto_atributo_variante (variante_id),
    INDEX idx_producto_atributo_definicion (categoria_atributo_id)
);

CREATE TABLE producto_atributo_opciones (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    producto_atributo_id VARCHAR(160) NOT NULL,
    opcion_id VARCHAR(160) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_atributo_opcion_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT fk_producto_atributo_opcion_atributo
        FOREIGN KEY (producto_atributo_id) REFERENCES producto_atributos(id) ON DELETE CASCADE,
    CONSTRAINT uq_producto_atributo_opcion UNIQUE (producto_atributo_id, opcion_id),
    INDEX idx_producto_atributo_opcion_producto (producto_id)
);

CREATE TABLE producto_presentaciones (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    sku VARCHAR(160) NOT NULL DEFAULT '',
    nombre VARCHAR(250) NOT NULL,
    equivalencia DECIMAL(24, 8) NOT NULL DEFAULT 1,
    unidad_base VARCHAR(80) NOT NULL DEFAULT 'UND',
    venta_minima DECIMAL(24, 8) NOT NULL DEFAULT 1,
    estado VARCHAR(20) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_presentacion_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    INDEX idx_producto_presentacion_producto (producto_id),
    INDEX idx_producto_presentacion_sku (sku)
);

CREATE TABLE producto_precios (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    sku VARCHAR(160) NOT NULL DEFAULT '',
    lista_precio VARCHAR(160) NOT NULL DEFAULT 'General',
    lista_precio_id VARCHAR(160) NOT NULL DEFAULT '',
    presentacion VARCHAR(250) NOT NULL DEFAULT 'Unidad',
    presentacion_id VARCHAR(160) NOT NULL DEFAULT '',
    moneda VARCHAR(12) NOT NULL DEFAULT 'PEN',
    impuesto DECIMAL(10, 4) NOT NULL DEFAULT 18,
    precio DECIMAL(24, 8) NULL,
    requiere_cotizacion BOOLEAN NOT NULL DEFAULT FALSE,
    configuracion VARCHAR(80) NOT NULL DEFAULT 'precio_fijo',
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_precio_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    INDEX idx_producto_precio_producto (producto_id),
    INDEX idx_producto_precio_sku (sku),
    INDEX idx_producto_precio_lista (lista_precio)
);

CREATE TABLE producto_imagenes (
    id VARCHAR(160) PRIMARY KEY,
    producto_id CHAR(36) NOT NULL,
    sku VARCHAR(160) NOT NULL DEFAULT '',
    storage_key VARCHAR(500) NOT NULL,
    tipo VARCHAR(80) NOT NULL DEFAULT 'PRODUCT',
    principal BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_producto_imagen_producto
        FOREIGN KEY (producto_id) REFERENCES products(id) ON DELETE CASCADE,
    CONSTRAINT uq_producto_imagen_storage UNIQUE (producto_id, storage_key),
    INDEX idx_producto_imagen_producto (producto_id),
    INDEX idx_producto_imagen_principal (producto_id, principal)
);
