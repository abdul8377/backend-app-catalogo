(() => {
  'use strict';

  const form = document.querySelector('[data-product-wizard]');
  if (!form) return;

  const jsonField = (name) => form.querySelector(`[name="${name}"]`);
  const parse = (name, fallback) => {
    try {
      const value = JSON.parse(jsonField(name)?.value || '');
      return value ?? fallback;
    } catch (_) {
      return fallback;
    }
  };
  const value = (name) => (form.elements[name]?.value || '').trim();
  const uid = (prefix) => `${prefix}-${crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2)}`;
  const number = (raw, fallback = null) => {
    if (raw === '' || raw === null || raw === undefined) return fallback;
    const parsed = Number(String(raw).replace(',', '.'));
    return Number.isFinite(parsed) ? parsed : fallback;
  };
  const escapeHtml = (raw) => String(raw ?? '')
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
  const activeStatus = (raw) => !['INACTIVE', '0', 'FALSE'].includes(String(raw || 'ACTIVE').toUpperCase());

  let attributes = parse('attributesJson', {});
  let variants = parse('variantsJson', []);
  let presentationRows = groupPresentations(parse('presentationsJson', []));
  let prices = parse('pricesJson', []);
  let images = parse('imagesJson', []);
  let currentStep = 0;

  const panels = [...document.querySelectorAll('[data-wizard-panel]')];
  const stepButtons = [...document.querySelectorAll('[data-wizard-step]')];
  const alertBox = document.querySelector('[data-wizard-alert]');
  const previousButton = document.querySelector('[data-wizard-previous]');
  const nextButton = document.querySelector('[data-wizard-next]');
  const submitButton = document.querySelector('[data-wizard-submit]');
  const productTypeInputs = [...form.querySelectorAll('[name="productType"]')];

  function groupPresentations(rows) {
    const grouped = new Map();
    (Array.isArray(rows) ? rows : []).forEach((row) => {
      const key = [row.id || '', row.name || row.nombre || 'Unidad', row.baseUnit || row.unidad || 'UND',
        row.equivalence ?? row.equivalencia ?? 1, row.minimumSale ?? row.minimumOrder ?? 1,
        row.purchaseIncrement ?? row.increment ?? 1].join('::');
      if (!grouped.has(key)) {
        grouped.set(key, {
          id: row.id || uid('presentation'),
          name: row.name || row.nombre || 'Unidad',
          baseUnit: row.baseUnit || row.unidad || 'UND',
          equivalence: number(row.equivalence ?? row.equivalencia, 1),
          minimumSale: number(row.minimumSale ?? row.minimumOrder, 1),
          purchaseIncrement: number(row.purchaseIncrement ?? row.increment, 1),
          allowsDecimals: Boolean(row.allowsDecimals),
          assignedSkus: []
        });
      }
      const sku = String(row.sku || '').trim().toUpperCase();
      if (sku) grouped.get(key).assignedSkus.push(sku);
    });
    return [...grouped.values()];
  }

  function selectedProductType() {
    return productTypeInputs.find((input) => input.checked)?.value || 'SINGLE';
  }

  function refreshTypeCards() {
    document.querySelectorAll('.product-type-option').forEach((card) => {
      card.classList.toggle('selected', Boolean(card.querySelector('input:checked')));
    });
    document.querySelector('[data-matrix-builder]')?.classList.toggle('hidden', selectedProductType() !== 'MATRIX');
  }

  function ensureSingleVariant() {
    if (selectedProductType() !== 'SINGLE') return;
    const productId = value('productId');
    const code = value('code').toUpperCase();
    const name = value('name');
    const existing = variants[0] || {};
    variants = [{
      id: existing.id || `${productId}:single`,
      sku: existing.sku || (code ? `${code}-U` : ''),
      supplierCode: existing.supplierCode || '',
      shortName: existing.shortName || name,
      status: existing.status || 'ACTIVE',
      attributes: existing.attributes && typeof existing.attributes === 'object' ? existing.attributes : {}
    }];
  }

  function variantAttributeText(data) {
    if (!data || typeof data !== 'object') return '';
    return Object.entries(data).map(([key, raw]) => {
      const object = raw && typeof raw === 'object' ? raw : {value: raw};
      const unit = object.unit || object.unidad || '';
      return `${key}=${object.value ?? object.valor ?? ''}${unit ? ' ' + unit : ''}`;
    }).join('; ');
  }

  function parseVariantAttributes(raw) {
    const result = {};
    String(raw || '').split(';').map((item) => item.trim()).filter(Boolean).forEach((item) => {
      const split = item.indexOf('=');
      if (split < 1) return;
      const name = item.slice(0, split).trim();
      const content = item.slice(split + 1).trim();
      const match = content.match(/^(.+?)\s+([a-zA-Z%°"']+)$/);
      result[name] = match ? {value: match[1].trim(), unit: match[2].trim()} : {value: content, unit: ''};
    });
    return result;
  }

  function renderAttributes() {
    const target = document.querySelector('[data-attributes]');
    if (!target) return;
    const rows = Object.entries(attributes || {});
    target.innerHTML = rows.length ? rows.map(([name, raw], index) => {
      const object = raw && typeof raw === 'object' ? raw : {value: raw};
      return `<div class="repeater-row attribute-row" data-attribute-index="${index}">
        <div class="row-field"><label>Atributo</label><input data-attribute-name value="${escapeHtml(name)}" placeholder="Material"></div>
        <div class="row-field"><label>Valor</label><input data-attribute-value value="${escapeHtml(object.value ?? object.valor ?? raw ?? '')}" placeholder="Acero"></div>
        <div class="row-field"><label>Unidad</label><input data-attribute-unit value="${escapeHtml(object.unit ?? object.unidad ?? '')}" placeholder="mm"></div>
        <button class="icon-button" type="button" data-remove-attribute="${index}" title="Eliminar">×</button>
      </div>`;
    }).join('') : '<div class="empty-repeater">No hay características comunes. Agrega únicamente las que correspondan a la categoría.</div>';
  }

  function renderVariants() {
    ensureSingleVariant();
    const target = document.querySelector('[data-variants]');
    if (!target) return;
    const single = selectedProductType() === 'SINGLE';
    target.innerHTML = variants.length ? variants.map((variant, index) => `<div class="repeater-row variant-row" data-variant-index="${index}">
      <div class="row-field"><label>SKU *</label><input data-variant-sku value="${escapeHtml(variant.sku || '')}" placeholder="SKU-001" ${single ? 'readonly' : ''}></div>
      <div class="row-field"><label>Código proveedor</label><input data-variant-supplier value="${escapeHtml(variant.supplierCode || '')}" placeholder="Código catálogo"></div>
      <div class="row-field"><label>Nombre corto *</label><input data-variant-name value="${escapeHtml(variant.shortName || '')}" placeholder="Nombre vendible"></div>
      <div class="row-field"><label>Atributos técnicos</label><input data-variant-attributes value="${escapeHtml(variantAttributeText(variant.attributes))}" placeholder="Rosca=M12; Largo=40 mm"></div>
      <label class="inline-check"><input type="checkbox" data-variant-active ${activeStatus(variant.status) ? 'checked' : ''}> Activa</label>
      ${single ? '<span></span>' : `<button class="icon-button" type="button" data-remove-variant="${index}" title="Eliminar">×</button>`}
    </div>`).join('') : '<div class="empty-repeater">Agrega al menos una variante vendible.</div>';
    document.querySelector('[data-add-variant]')?.toggleAttribute('hidden', single);
  }

  function renderPresentations() {
    const target = document.querySelector('[data-presentations]');
    if (!target) return;
    target.innerHTML = presentationRows.length ? presentationRows.map((row, index) => `<div class="repeater-row presentation-row" data-presentation-index="${index}">
      <div class="row-field"><label>Presentación *</label><input data-presentation-name value="${escapeHtml(row.name)}" placeholder="Caja x100"></div>
      <div class="row-field"><label>Unidad base</label><input data-presentation-unit value="${escapeHtml(row.baseUnit)}" placeholder="PZA"></div>
      <div class="row-field"><label>Equivalencia</label><input type="number" min="0.0001" step="any" data-presentation-equivalence value="${row.equivalence ?? 1}"></div>
      <div class="row-field"><label>Venta mínima</label><input type="number" min="0.0001" step="any" data-presentation-minimum value="${row.minimumSale ?? 1}"></div>
      <div class="row-field"><label>Incremento</label><input type="number" min="0.0001" step="any" data-presentation-increment value="${row.purchaseIncrement ?? 1}"></div>
      <div class="row-field"><label>SKU asignados</label><input data-presentation-skus value="${escapeHtml((row.assignedSkus || []).join(', '))}" placeholder="Vacío = todas; o SKU-1, SKU-2"></div>
      <button class="icon-button" type="button" data-remove-presentation="${index}" title="Eliminar">×</button>
    </div>`).join('') : '<div class="empty-repeater">Añade cómo pide el cliente el producto: unidad, ciento, caja, kilogramo, metro, rollo, etc.</div>';
  }

  function presentationCombinations() {
    const active = variants.filter((variant) => activeStatus(variant.status));
    const combinations = [];
    presentationRows.forEach((presentation) => {
      const assigned = (presentation.assignedSkus || []).map((sku) => sku.toUpperCase());
      active.filter((variant) => !assigned.length || assigned.includes(String(variant.sku).toUpperCase())).forEach((variant) => {
        combinations.push({variant, presentation});
      });
    });
    return combinations;
  }

  function ensurePriceRows() {
    const combinations = presentationCombinations();
    const current = new Map((Array.isArray(prices) ? prices : []).map((row) => [
      `${String(row.sku || '').toUpperCase()}::${String(row.presentation || row.presentacion || '').toLowerCase()}`,
      row
    ]));
    prices = combinations.map(({variant, presentation}) => {
      const key = `${String(variant.sku).toUpperCase()}::${String(presentation.name).toLowerCase()}`;
      const existing = current.get(key) || {};
      return {
        sku: variant.sku,
        variantId: variant.id,
        priceList: existing.priceList || existing.listaPrecio || 'General',
        presentation: presentation.name,
        presentationId: presentation.id,
        currency: existing.currency || 'PEN',
        taxRate: number(existing.taxRate, 18),
        price: existing.price ?? existing.valor ?? null,
        quoteRequired: Boolean(existing.quoteRequired),
        configuration: existing.configuration || (existing.quoteRequired ? 'por_cotizar' : 'precio_fijo')
      };
    });
  }

  function renderPrices() {
    ensurePriceRows();
    const target = document.querySelector('[data-prices]');
    if (!target) return;
    target.innerHTML = prices.length ? prices.map((row, index) => `<div class="repeater-row price-row" data-price-index="${index}">
      <div class="row-field"><label>Variante</label><input value="${escapeHtml(row.sku)}" readonly></div>
      <div class="row-field"><label>Presentación</label><input value="${escapeHtml(row.presentation)}" readonly></div>
      <div class="row-field"><label>Lista</label><input data-price-list value="${escapeHtml(row.priceList || 'General')}"></div>
      <div class="row-field"><label>Moneda</label><select data-price-currency><option value="PEN" ${row.currency === 'PEN' ? 'selected' : ''}>PEN</option><option value="USD" ${row.currency === 'USD' ? 'selected' : ''}>USD</option></select></div>
      <div class="row-field"><label>Configuración</label><select data-price-configuration><option value="precio_fijo" ${!row.quoteRequired ? 'selected' : ''}>Precio fijo</option><option value="por_cotizar" ${row.quoteRequired ? 'selected' : ''}>Por cotizar</option></select></div>
      <div class="row-field"><label>Precio</label><input type="number" min="0" step="0.01" data-price-value value="${row.price ?? ''}" ${row.quoteRequired ? 'disabled' : ''}></div>
      <span></span>
    </div>`).join('') : '<div class="empty-repeater">Primero registra variantes y presentaciones vendibles.</div>';
  }

  function storageUrl(image) {
    if (image.url) return image.url;
    const parts = String(image.storageKey || '').split('/');
    return parts.length > 1 ? `/public/files/${parts[1]}` : '';
  }

  function renderImages() {
    const target = document.querySelector('[data-image-gallery]');
    if (!target) return;
    target.innerHTML = images.length ? images.map((image, index) => `<article class="image-tile" data-image-index="${index}">
      ${storageUrl(image) ? `<img src="${escapeHtml(storageUrl(image))}" alt="Imagen del producto">` : '<div class="empty-repeater">Imagen</div>'}
      ${image.primary ? '<span class="primary-label">Principal</span>' : ''}
      <small>${escapeHtml(image.fileName || image.storageKey || 'Imagen')}</small>
      <div><label class="inline-check"><input type="radio" name="existingPrimary" data-existing-primary="${index}" ${image.primary ? 'checked' : ''}> Usar como principal</label>
      <button class="icon-button" type="button" data-remove-image="${index}" title="Quitar">×</button></div>
    </article>`).join('') : '<div class="empty-repeater">Aún no hay imágenes guardadas. Puedes registrar un borrador sin imágenes.</div>';
  }

  function renderSelectedFiles() {
    const input = form.querySelector('[name="imageFiles"]');
    const target = document.querySelector('[data-new-images]');
    if (!input || !target) return;
    target.innerHTML = '';
    [...input.files].forEach((file, index) => {
      const tile = document.createElement('article');
      tile.className = 'image-tile';
      const url = URL.createObjectURL(file);
      tile.innerHTML = `<img src="${url}" alt="Vista previa"><small>${escapeHtml(file.name)}</small>
        <label class="inline-check"><input type="radio" name="uploadedPrimaryChoice" value="${index}" ${index === 0 && !images.some((image) => image.primary) ? 'checked' : ''}> Principal nueva</label>`;
      target.appendChild(tile);
    });
    const checked = form.querySelector('[name="uploadedPrimaryChoice"]:checked');
    jsonField('primaryUploadedImage').value = checked ? checked.value : '-1';
  }

  function readAttributesFromDom() {
    const next = {};
    document.querySelectorAll('[data-attribute-index]').forEach((row) => {
      const name = row.querySelector('[data-attribute-name]').value.trim();
      if (!name) return;
      next[name] = {
        value: row.querySelector('[data-attribute-value]').value.trim(),
        unit: row.querySelector('[data-attribute-unit]').value.trim()
      };
    });
    attributes = next;
  }

  function readVariantsFromDom() {
    variants = [...document.querySelectorAll('[data-variant-index]')].map((row, index) => {
      const existing = variants[index] || {};
      return {
        id: existing.id || uid('variant'),
        sku: row.querySelector('[data-variant-sku]').value.trim().toUpperCase(),
        supplierCode: row.querySelector('[data-variant-supplier]').value.trim(),
        shortName: row.querySelector('[data-variant-name]').value.trim(),
        status: row.querySelector('[data-variant-active]').checked ? 'ACTIVE' : 'INACTIVE',
        attributes: parseVariantAttributes(row.querySelector('[data-variant-attributes]').value)
      };
    });
  }

  function readPresentationsFromDom() {
    presentationRows = [...document.querySelectorAll('[data-presentation-index]')].map((row, index) => {
      const existing = presentationRows[index] || {};
      return {
        id: existing.id || uid('presentation'),
        name: row.querySelector('[data-presentation-name]').value.trim(),
        baseUnit: row.querySelector('[data-presentation-unit]').value.trim().toUpperCase() || 'UND',
        equivalence: number(row.querySelector('[data-presentation-equivalence]').value, 1),
        minimumSale: number(row.querySelector('[data-presentation-minimum]').value, 1),
        purchaseIncrement: number(row.querySelector('[data-presentation-increment]').value, 1),
        allowsDecimals: false,
        assignedSkus: row.querySelector('[data-presentation-skus]').value.split(',').map((item) => item.trim().toUpperCase()).filter(Boolean)
      };
    });
  }

  function readPricesFromDom() {
    prices = [...document.querySelectorAll('[data-price-index]')].map((row, index) => {
      const existing = prices[index];
      const quote = row.querySelector('[data-price-configuration]').value === 'por_cotizar';
      return {
        ...existing,
        priceList: row.querySelector('[data-price-list]').value.trim() || 'General',
        currency: row.querySelector('[data-price-currency]').value,
        taxRate: 18,
        price: quote ? null : number(row.querySelector('[data-price-value]').value),
        quoteRequired: quote,
        configuration: quote ? 'por_cotizar' : 'precio_fijo'
      };
    });
  }

  function expandedPresentations() {
    const activeSkus = variants.filter((variant) => activeStatus(variant.status)).map((variant) => String(variant.sku).toUpperCase());
    return presentationRows.flatMap((row) => {
      const assigned = row.assignedSkus.length ? row.assignedSkus : activeSkus;
      return assigned.map((sku) => ({
        id: `${row.id}:${sku}`,
        sku,
        name: row.name,
        baseUnit: row.baseUnit,
        equivalence: row.equivalence,
        minimumSale: row.minimumSale,
        purchaseIncrement: row.purchaseIncrement,
        allowsDecimals: row.allowsDecimals,
        status: 'ACTIVE'
      }));
    });
  }

  function syncHiddenFields() {
    readAttributesFromDom();
    readVariantsFromDom();
    readPresentationsFromDom();
    if (currentStep >= 3) readPricesFromDom();

    jsonField('attributesJson').value = JSON.stringify(attributes);
    jsonField('variantsJson').value = JSON.stringify(variants);
    const expanded = expandedPresentations();
    jsonField('presentationsJson').value = JSON.stringify(expanded);
    jsonField('pricesJson').value = JSON.stringify(prices);
    jsonField('imagesJson').value = JSON.stringify(images);

    const variantBySku = new Map(variants.map((variant) => [String(variant.sku).toUpperCase(), variant]));
    const salesConfiguration = {
      presentations: presentationRows.map((row) => ({
        id: row.id,
        name: row.name,
        base_unit: row.baseUnit,
        equivalent_to: row.equivalence,
        minimum_order: row.minimumSale,
        purchase_increment: row.purchaseIncrement,
        allows_decimals: row.allowsDecimals,
        assigned_variant_ids: (row.assignedSkus.length ? row.assignedSkus : [...variantBySku.keys()])
          .map((sku) => variantBySku.get(String(sku).toUpperCase())?.id).filter(Boolean),
        default_variant_ids: [],
        variant_rules: []
      })),
      uses_logistics_packages: false,
      logistics_packages: [],
      has_product_content: false,
      content_items: []
    };
    jsonField('salesConfigurationJson').value = JSON.stringify(salesConfiguration);

    const lists = [...new Set(prices.map((row) => row.priceList || 'General'))].map((name) => ({
      id: `list-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
      name,
      currency_code: prices.find((row) => (row.priceList || 'General') === name)?.currency || 'PEN',
      includes_igv: true,
      valid_from: new Date().toISOString(),
      valid_until: null
    }));
    const listByName = new Map(lists.map((item) => [item.name, item.id]));
    const pricingConfiguration = {
      lists,
      prices: prices.map((row) => ({
        list_id: listByName.get(row.priceList || 'General'),
        variant_id: row.variantId,
        presentation_id: row.presentationId,
        configuration: row.quoteRequired ? 'quote' : 'fixed',
        fixed_price: row.quoteRequired ? null : row.price,
        ranges: []
      })),
      sellable_combinations: presentationCombinations().map(({variant, presentation}) => ({
        variant_id: variant.id,
        variant_label: variant.shortName,
        presentation_id: presentation.id,
        presentation_label: presentation.name,
        base_unit: presentation.baseUnit,
        equivalent_to_base_unit: presentation.equivalence,
        minimum_order: presentation.minimumSale,
        purchase_increment: presentation.purchaseIncrement
      }))
    };
    jsonField('pricingConfigurationJson').value = JSON.stringify(pricingConfiguration);
    jsonField('imageConfigurationJson').value = JSON.stringify({remote_images: images});
  }

  function validateStep(step) {
    syncHiddenFields();
    if (step === 0) {
      if (!value('company') || !value('brand') || !value('category')) return 'Completa empresa, marca y categoría.';
    }
    if (step === 1) {
      if (!value('code') || !value('name')) return 'Completa el código y el nombre comercial.';
      if (!variants.length) return 'Registra al menos una variante.';
      if (selectedProductType() === 'SINGLE' && variants.length !== 1) return 'Un producto único debe tener exactamente una variante.';
      const skus = variants.map((item) => String(item.sku).trim().toUpperCase());
      if (skus.some((sku) => !sku)) return 'Todas las variantes necesitan un SKU.';
      if (new Set(skus).size !== skus.length) return 'Hay SKU repetidos.';
      if (variants.some((item) => !String(item.shortName || '').trim())) return 'Completa el nombre corto de todas las variantes.';
      if (!variants.some((item) => activeStatus(item.status))) return 'Activa al menos una variante.';
    }
    if (step === 2) {
      if (!presentationRows.length) return 'Agrega al menos una presentación vendible.';
      if (presentationRows.some((item) => !item.name || item.equivalence <= 0 || item.minimumSale <= 0 || item.purchaseIncrement <= 0)) {
        return 'Revisa nombre, equivalencia, venta mínima e incremento de las presentaciones.';
      }
      if (!presentationCombinations().length) return 'Asigna una presentación a cada variante activa.';
    }
    if (step === 3 && value('status') === 'ACTIVE') {
      if (prices.some((item) => !item.quoteRequired && (item.price === null || item.price < 0))) {
        return 'Configura todos los precios o marca las combinaciones por cotizar.';
      }
    }
    if (step === 4 && value('status') === 'ACTIVE') {
      const hasExistingPrimary = images.some((image) => image.primary);
      const hasUpload = form.querySelector('[name="imageFiles"]')?.files.length > 0;
      if (!hasExistingPrimary && !hasUpload) return 'Para activar, agrega una imagen principal.';
    }
    return null;
  }

  function showAlert(message) {
    alertBox.textContent = message || '';
    alertBox.classList.toggle('visible', Boolean(message));
    if (message) alertBox.scrollIntoView({behavior: 'smooth', block: 'nearest'});
  }

  function refreshReview() {
    syncHiddenFields();
    const activeVariants = variants.filter((variant) => activeStatus(variant.status));
    const fixed = prices.filter((price) => !price.quoteRequired && price.price !== null).length;
    const quote = prices.filter((price) => price.quoteRequired).length;
    document.querySelector('[data-review-classification]').textContent = [value('company'), value('brand'), value('category'), value('subcategory')].filter(Boolean).join(' · ');
    document.querySelector('[data-review-product]').textContent = `${value('code')} · ${value('name')} · ${selectedProductType()}`;
    document.querySelector('[data-review-variants]').textContent = `${activeVariants.length} activas de ${variants.length}`;
    document.querySelector('[data-review-presentations]').textContent = `${presentationRows.length} presentaciones · ${presentationCombinations().length} combinaciones`;
    document.querySelector('[data-review-prices]').textContent = `${fixed} con precio · ${quote} por cotizar`;
    document.querySelector('[data-review-images]').textContent = `${images.length} guardadas · ${form.querySelector('[name="imageFiles"]')?.files.length || 0} nuevas`;
  }

  function showStep(step) {
    currentStep = Math.max(0, Math.min(5, step));
    panels.forEach((panel, index) => panel.classList.toggle('active', index === currentStep));
    stepButtons.forEach((button, index) => {
      button.classList.toggle('active', index === currentStep);
      button.classList.toggle('done', index < currentStep);
    });
    previousButton.hidden = currentStep === 0;
    nextButton.hidden = currentStep === 5;
    submitButton.hidden = currentStep !== 5;
    if (currentStep === 3) renderPrices();
    if (currentStep === 4) { renderImages(); renderSelectedFiles(); }
    if (currentStep === 5) refreshReview();
    showAlert(null);
    window.scrollTo({top: 0, behavior: 'smooth'});
  }

  function generateMatrix() {
    readVariantsFromDom();
    const axisA = value('matrixAxisAName') || 'Atributo A';
    const axisB = value('matrixAxisBName') || 'Atributo B';
    const valuesA = value('matrixAxisAValues').split(',').map((item) => item.trim()).filter(Boolean);
    const valuesB = value('matrixAxisBValues').split(',').map((item) => item.trim()).filter(Boolean);
    if (!valuesA.length || !valuesB.length) {
      showAlert('Escribe valores para ambos ejes de la matriz.');
      return;
    }
    const baseCode = value('code').toUpperCase() || 'PROD';
    const baseName = value('name') || 'Producto';
    variants = [];
    valuesA.forEach((a, row) => valuesB.forEach((b, column) => variants.push({
      id: uid('variant'),
      sku: `${baseCode}-${row + 1}${column + 1}`,
      supplierCode: '',
      shortName: `${baseName} ${a} ${b}`,
      status: 'ACTIVE',
      attributes: {[axisA]: {value: a, unit: ''}, [axisB]: {value: b, unit: ''}}
    })));
    renderVariants();
  }

  form.addEventListener('input', (event) => {
    if (event.target.name === 'code' || event.target.name === 'name') {
      if (selectedProductType() === 'SINGLE') renderVariants();
    }
  });

  productTypeInputs.forEach((input) => input.addEventListener('change', () => {
    refreshTypeCards();
    if (selectedProductType() === 'SINGLE') ensureSingleVariant();
    else if (variants.length === 1 && String(variants[0].id || '').endsWith(':single')) variants = [];
    presentationRows = [];
    prices = [];
    renderVariants(); renderPresentations();
  }));

  document.querySelector('[data-add-attribute]')?.addEventListener('click', () => {
    readAttributesFromDom(); attributes[`Atributo ${Object.keys(attributes).length + 1}`] = {value: '', unit: ''}; renderAttributes();
  });
  document.querySelector('[data-add-variant]')?.addEventListener('click', () => {
    readVariantsFromDom(); variants.push({id: uid('variant'), sku: '', supplierCode: '', shortName: '', status: 'ACTIVE', attributes: {}}); renderVariants();
  });
  document.querySelector('[data-add-presentation]')?.addEventListener('click', () => {
    readPresentationsFromDom(); presentationRows.push({id: uid('presentation'), name: '', baseUnit: 'UND', equivalence: 1, minimumSale: 1, purchaseIncrement: 1, allowsDecimals: false, assignedSkus: []}); renderPresentations();
  });
  document.querySelector('[data-generate-matrix]')?.addEventListener('click', generateMatrix);

  form.addEventListener('click', (event) => {
    const removeAttribute = event.target.closest('[data-remove-attribute]');
    if (removeAttribute) { readAttributesFromDom(); const key = Object.keys(attributes)[Number(removeAttribute.dataset.removeAttribute)]; delete attributes[key]; renderAttributes(); }
    const removeVariant = event.target.closest('[data-remove-variant]');
    if (removeVariant) { readVariantsFromDom(); variants.splice(Number(removeVariant.dataset.removeVariant), 1); presentationRows = []; prices = []; renderVariants(); renderPresentations(); }
    const removePresentation = event.target.closest('[data-remove-presentation]');
    if (removePresentation) { readPresentationsFromDom(); presentationRows.splice(Number(removePresentation.dataset.removePresentation), 1); prices = []; renderPresentations(); }
    const removeImage = event.target.closest('[data-remove-image]');
    if (removeImage) { images.splice(Number(removeImage.dataset.removeImage), 1); if (images.length && !images.some((item) => item.primary)) images[0].primary = true; renderImages(); }
  });

  form.addEventListener('change', (event) => {
    if (event.target.matches('[data-price-configuration]')) {
      const row = event.target.closest('[data-price-index]');
      row.querySelector('[data-price-value]').disabled = event.target.value === 'por_cotizar';
    }
    if (event.target.matches('[data-existing-primary]')) {
      const selected = Number(event.target.dataset.existingPrimary);
      images = images.map((image, index) => ({...image, primary: index === selected}));
      renderImages();
      jsonField('primaryUploadedImage').value = '-1';
    }
    if (event.target.name === 'imageFiles') renderSelectedFiles();
    if (event.target.name === 'uploadedPrimaryChoice') jsonField('primaryUploadedImage').value = event.target.value;
  });

  previousButton.addEventListener('click', () => showStep(currentStep - 1));
  nextButton.addEventListener('click', () => {
    const error = validateStep(currentStep);
    if (error) return showAlert(error);
    showStep(currentStep + 1);
  });
  stepButtons.forEach((button, index) => button.addEventListener('click', () => {
    if (index <= currentStep) showStep(index);
  }));

  form.addEventListener('submit', (event) => {
    for (let step = 0; step <= 4; step++) {
      const error = validateStep(step);
      if (error) {
        event.preventDefault(); showStep(step); showAlert(error); return;
      }
    }
    syncHiddenFields();
  });

  refreshTypeCards();
  renderAttributes();
  renderVariants();
  renderPresentations();
  renderImages();
  showStep(0);
})();
