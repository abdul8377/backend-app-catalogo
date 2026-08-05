(() => {
  'use strict';
  const form = document.querySelector('[data-product-wizard]');
  if (!form) return;

  const field = (name) => form.querySelector(`[name="${name}"]`);
  const read = (name, fallback) => {
    try { return JSON.parse(field(name)?.value || '') ?? fallback; }
    catch (_) { return fallback; }
  };
  const originalSales = read('salesConfigurationJson', {});
  const originalPricing = read('pricingConfigurationJson', {});
  const originalImages = read('imageConfigurationJson', {});

  form.addEventListener('submit', () => {
    const sales = read('salesConfigurationJson', {});
    field('salesConfigurationJson').value = JSON.stringify({
      ...originalSales,
      ...sales,
      uses_logistics_packages: originalSales.uses_logistics_packages ?? sales.uses_logistics_packages ?? false,
      logistics_packages: originalSales.logistics_packages ?? sales.logistics_packages ?? [],
      has_product_content: originalSales.has_product_content ?? sales.has_product_content ?? false,
      content_items: originalSales.content_items ?? sales.content_items ?? []
    });

    const pricing = read('pricingConfigurationJson', {});
    const originalByKey = new Map((originalPricing.prices || []).map((item) => [
      `${item.list_id || ''}::${item.variant_id || ''}::${item.presentation_id || ''}`,
      item
    ]));
    const mergedPrices = (pricing.prices || []).map((item) => {
      const key = `${item.list_id || ''}::${item.variant_id || ''}::${item.presentation_id || ''}`;
      const previous = originalByKey.get(key);
      return previous?.configuration === 'quantity' ? previous : item;
    });
    const listMap = new Map([...(originalPricing.lists || []), ...(pricing.lists || [])]
      .map((item) => [item.id || item.name, item]));
    field('pricingConfigurationJson').value = JSON.stringify({
      ...originalPricing,
      ...pricing,
      lists: [...listMap.values()],
      prices: mergedPrices
    });

    const imageConfiguration = read('imageConfigurationJson', {});
    field('imageConfigurationJson').value = JSON.stringify({
      ...originalImages,
      ...imageConfiguration,
      remote_images: imageConfiguration.remote_images || []
    });
  });
})();
