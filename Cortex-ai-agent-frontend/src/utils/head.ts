const SITE_NAME = 'Cortex AI'

export interface PageMeta {
  title: string
  description?: string
}

export function setPageMeta({ title, description }: PageMeta) {
  const fullTitle = title === SITE_NAME ? title : `${title} - ${SITE_NAME}`
  document.title = fullTitle

  let metaDesc = document.querySelector('meta[name="description"]')
  if (!metaDesc) {
    metaDesc = document.createElement('meta')
    metaDesc.setAttribute('name', 'description')
    document.head.appendChild(metaDesc)
  }
  metaDesc.setAttribute('content', description ?? '')

  const ogTitle = document.querySelector('meta[property="og:title"]') || document.createElement('meta')
  if (!ogTitle.getAttribute('property')) ogTitle.setAttribute('property', 'og:title')
  ogTitle.setAttribute('content', fullTitle)
  if (!ogTitle.parentNode) document.head.appendChild(ogTitle)

  if (description) {
    const ogDesc = document.querySelector('meta[property="og:description"]') || document.createElement('meta')
    if (!ogDesc.getAttribute('property')) ogDesc.setAttribute('property', 'og:description')
    ogDesc.setAttribute('content', description)
    if (!ogDesc.parentNode) document.head.appendChild(ogDesc)
  }
}
