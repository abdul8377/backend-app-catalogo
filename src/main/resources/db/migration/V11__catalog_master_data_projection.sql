CREATE TABLE empresas (
    id VARCHAR(160) PRIMARY KEY,
    nombre VARCHAR(250) NOT NULL,
    nombre_normalizado VARCHAR(250) NOT NULL,
    ruc VARCHAR(40) NOT NULL DEFAULT '',
    telefono VARCHAR(80) NOT NULL DEFAULT '',
    direccion VARCHAR(500) NOT NULL DEFAULT '',
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_empresa_nombre UNIQUE (nombre_normalizado)
);

CREATE TABLE marcas (
    id VARCHAR(160) PRIMARY KEY,
    empresa_id VARCHAR(160) NOT NULL,
    nombre VARCHAR(250) NOT NULL,
    nombre_normalizado VARCHAR(250) NOT NULL,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_marca_empresa FOREIGN KEY (empresa_id) REFERENCES empresas(id),
    CONSTRAINT uq_marca_empresa_nombre UNIQUE (empresa_id, nombre_normalizado)
);
CREATE INDEX idx_marcas_empresa ON marcas(empresa_id, estado, deleted);

CREATE TABLE categorias (
    id VARCHAR(160) PRIMARY KEY,
    categoria_padre_id VARCHAR(160) NULL,
    nombre VARCHAR(250) NOT NULL,
    nombre_normalizado VARCHAR(250) NOT NULL,
    descripcion VARCHAR(1000) NOT NULL DEFAULT '',
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_categoria_padre FOREIGN KEY (categoria_padre_id) REFERENCES categorias(id)
);
CREATE INDEX idx_categorias_padre ON categorias(categoria_padre_id, estado, deleted);
CREATE INDEX idx_categorias_nombre ON categorias(nombre_normalizado, estado, deleted);

CREATE TABLE marca_categorias (
    id VARCHAR(160) PRIMARY KEY,
    marca_id VARCHAR(160) NOT NULL,
    categoria_id VARCHAR(160) NOT NULL,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_marca_categoria_marca FOREIGN KEY (marca_id) REFERENCES marcas(id),
    CONSTRAINT fk_marca_categoria_categoria FOREIGN KEY (categoria_id) REFERENCES categorias(id),
    CONSTRAINT uq_marca_categoria UNIQUE (marca_id, categoria_id)
);
CREATE INDEX idx_marca_categorias_categoria ON marca_categorias(categoria_id, estado, deleted);

CREATE TABLE unidades_medida (
    id VARCHAR(160) PRIMARY KEY,
    codigo VARCHAR(80) NOT NULL,
    nombre VARCHAR(200) NOT NULL,
    simbolo VARCHAR(40) NOT NULL,
    magnitud VARCHAR(120) NOT NULL,
    factor_a_base DECIMAL(24, 8) NOT NULL,
    decimales INT NOT NULL DEFAULT 3,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_unidad_codigo UNIQUE (codigo)
);

CREATE TABLE categoria_atributos (
    id VARCHAR(160) PRIMARY KEY,
    categoria_id VARCHAR(160) NOT NULL,
    nombre VARCHAR(250) NOT NULL,
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
    magnitud VARCHAR(120) NULL,
    maximo_selecciones INT NULL,
    etiqueta_verdadero VARCHAR(160) NULL,
    etiqueta_falso VARCHAR(160) NULL,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_categoria_atributo_categoria FOREIGN KEY (categoria_id) REFERENCES categorias(id),
    CONSTRAINT uq_categoria_atributo_nombre UNIQUE (categoria_id, nombre),
    CONSTRAINT uq_categoria_atributo_clave UNIQUE (categoria_id, clave)
);
CREATE INDEX idx_categoria_atributos_categoria ON categoria_atributos(categoria_id, estado, orden);

CREATE TABLE categoria_atributo_opciones (
    id VARCHAR(160) PRIMARY KEY,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    etiqueta VARCHAR(250) NOT NULL,
    codigo VARCHAR(160) NOT NULL,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_atributo_opcion_atributo FOREIGN KEY (categoria_atributo_id) REFERENCES categoria_atributos(id),
    CONSTRAINT uq_atributo_opcion_etiqueta UNIQUE (categoria_atributo_id, etiqueta),
    CONSTRAINT uq_atributo_opcion_codigo UNIQUE (categoria_atributo_id, codigo)
);
CREATE INDEX idx_atributo_opciones_atributo ON categoria_atributo_opciones(categoria_atributo_id, estado);

CREATE TABLE categoria_atributo_unidades (
    id VARCHAR(160) PRIMARY KEY,
    categoria_atributo_id VARCHAR(160) NOT NULL,
    unidad_medida_id VARCHAR(160) NOT NULL,
    es_predeterminada BOOLEAN NOT NULL DEFAULT FALSE,
    orden INT NOT NULL DEFAULT 0,
    estado BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_atributo_unidad_atributo FOREIGN KEY (categoria_atributo_id) REFERENCES categoria_atributos(id),
    CONSTRAINT fk_atributo_unidad_unidad FOREIGN KEY (unidad_medida_id) REFERENCES unidades_medida(id),
    CONSTRAINT uq_atributo_unidad UNIQUE (categoria_atributo_id, unidad_medida_id)
);
CREATE INDEX idx_atributo_unidades_atributo ON categoria_atributo_unidades(categoria_atributo_id, estado, orden);

ALTER TABLE products ADD COLUMN company_id VARCHAR(160) NULL;
ALTER TABLE products ADD COLUMN brand_id VARCHAR(160) NULL;
ALTER TABLE products ADD COLUMN category_id VARCHAR(160) NULL;
ALTER TABLE products ADD COLUMN subcategory VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE products ADD COLUMN subcategory_id VARCHAR(160) NULL;
ALTER TABLE products ADD COLUMN product_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE';

ALTER TABLE products ADD CONSTRAINT fk_product_company FOREIGN KEY (company_id) REFERENCES empresas(id);
ALTER TABLE products ADD CONSTRAINT fk_product_brand FOREIGN KEY (brand_id) REFERENCES marcas(id);
ALTER TABLE products ADD CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES categorias(id);
ALTER TABLE products ADD CONSTRAINT fk_product_subcategory FOREIGN KEY (subcategory_id) REFERENCES categorias(id);

CREATE INDEX idx_product_company_id ON products(company_id);
CREATE INDEX idx_product_brand_id ON products(brand_id);
CREATE INDEX idx_product_category_id ON products(category_id, subcategory_id);
