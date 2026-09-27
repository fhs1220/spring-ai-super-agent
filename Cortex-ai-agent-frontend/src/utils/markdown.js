import MarkdownIt from 'markdown-it'

// Model text is untrusted: no raw HTML, plugins, automatic links, or remote images.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false, typographer: false })
const defaultValidateLink = markdown.validateLink.bind(markdown)
markdown.validateLink = (url) => defaultValidateLink(url) && /^(?:https?:\/\/|mailto:|#)/i.test(url)
markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index].content)
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('rel', 'noopener noreferrer')
  tokens[index].attrSet('target', '_blank')
  return self.renderToken(tokens, index, options)
}

/** User messages stay ordinary Vue text interpolation. */
export function renderAssistantMarkdown(value) {
  return markdown.render(typeof value === 'string' ? value : '')
}
