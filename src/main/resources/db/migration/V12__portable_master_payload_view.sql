CREATE OR REPLACE VIEW sync_master_payloads AS
SELECT 'COMPANY' entity_type, id entity_id,
       CONCAT(JSON_OBJECT('id', id, 'nombre', nombre, 'ruc', ruc, 'telefono', telefono,
            'direccion', direccion, 'estado', estado), '') payload_json
FROM empresas
UNION ALL
SELECT 'BRAND', id,
       CONCAT(JSON_OBJECT('id', id, 'empresa_id', empresa_id, 'nombre', nombre, 'estado', estado), '')
FROM marcas
UNION ALL
SELECT 'CATEGORY', id,
       CONCAT(JSON_OBJECT('id', id, 'categoria_padre_id', categoria_padre_id, 'nombre', nombre,
            'descripcion', descripcion, 'estado', estado), '')
FROM categorias
UNION ALL
SELECT 'BRAND_CATEGORY', id,
       CONCAT(JSON_OBJECT('id', id, 'marca_id', marca_id, 'categoria_id', categoria_id,
            'estado', estado), '')
FROM marca_categorias
UNION ALL
SELECT 'MEASUREMENT_UNIT', id,
       CONCAT(JSON_OBJECT('id', id, 'codigo', codigo, 'nombre', nombre, 'simbolo', simbolo,
            'magnitud', magnitud, 'factor_a_base', factor_a_base, 'decimales', decimales, 'estado', estado), '')
FROM unidades_medida
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE', id,
       CONCAT(JSON_OBJECT('id', id, 'categoria_id', categoria_id, 'nombre', nombre, 'clave', clave,
            'ayuda', ayuda, 'tipo_dato', tipo_dato, 'nivel_captura', nivel_captura,
            'requerido_activar', requerido_activar, 'visible_ficha', visible_ficha, 'filtrable', filtrable,
            'puede_ser_eje', puede_ser_eje, 'activo_nuevos', activo_nuevos,
            'longitud_maxima', longitud_maxima, 'ejemplo', ejemplo, 'minimo', minimo, 'maximo', maximo,
            'decimales', decimales, 'magnitud', magnitud, 'maximo_selecciones', maximo_selecciones,
            'etiqueta_verdadero', etiqueta_verdadero, 'etiqueta_falso', etiqueta_falso,
            'orden', orden, 'estado', estado), '')
FROM categoria_atributos
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE_OPTION', id,
       CONCAT(JSON_OBJECT('id', id, 'categoria_atributo_id', categoria_atributo_id, 'etiqueta', etiqueta,
            'codigo', codigo, 'orden', orden, 'estado', estado), '')
FROM categoria_atributo_opciones
UNION ALL
SELECT 'CATEGORY_ATTRIBUTE_UNIT', id,
       CONCAT(JSON_OBJECT('id', id, 'categoria_atributo_id', categoria_atributo_id,
            'unidad_medida_id', unidad_medida_id, 'es_predeterminada', es_predeterminada,
            'orden', orden, 'estado', estado), '')
FROM categoria_atributo_unidades
UNION ALL
SELECT 'LEGACY_ATTRIBUTE_DEFINITION', id,
       CONCAT(JSON_OBJECT('id', id, 'categoria_id', categoria_id, 'nombre', nombre,
            'tipo', tipo, 'es_variante', es_variante), '')
FROM atributos_def
UNION ALL
SELECT 'PRICE_LIST', id,
       CONCAT(JSON_OBJECT('id', id, 'nombre', nombre, 'moneda', moneda, 'incluye_igv', incluye_igv,
            'igv_porcentaje', igv_porcentaje, 'estado', estado), '')
FROM listas_precios;
