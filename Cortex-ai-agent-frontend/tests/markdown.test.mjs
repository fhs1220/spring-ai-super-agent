import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { renderAssistantMarkdown as render } from '../src/utils/markdown.js'

test('renders headings, strong emphasis, lists and citations without changing raw data', () => {
  const raw = '### 现在先做什么\n1. **联系可信的人**。[来源 1]\n2. 保留记录。'
  const html = render(raw)
  assert.match(html, /<h3>现在先做什么<\/h3>/)
  assert.match(html, /<ol>/)
  assert.match(html, /<strong>联系可信的人<\/strong>/)
  assert.match(html, /\[来源 1\]/)
  assert.ok(raw.includes('**'))
})

test('renders comparison tables and multiline streaming text', () => {
  assert.match(render('| 指标 | A | B |\n| --- | --- | --- |\n| 学习小时 | 2 | 3 |'), /<table>/)
  assert.match(render('第一行\n第二行'), /<br>/)
  for (const prefix of ['', '**', '**部分', '[来源 ', '```js\nconst']) assert.equal(typeof render(prefix), 'string')
})

test('escapes raw HTML, SVG and event handlers', () => {
  const html = render('<script>alert(1)</script>\n<svg onload="alert(1)"></svg>\n<img src=x onerror=alert(1)>')
  assert.doesNotMatch(html, /<(?:script|svg|img)\b/i)
  assert.match(html, /&lt;script&gt;/)
})

test('does not render dangerous, relative or protocol-relative navigation', () => {
  for (const url of ['javascript:alert(1)', 'jav&#x61;script:alert(1)', 'data:text/html,evil',
    'vbscript:evil', 'file:///etc/passwd', '//evil.example/a', '/api/delete', 'JaVaScRiPt:evil']) {
    assert.doesNotMatch(render(`[打开](${url})`), /<a\b/)
  }
})

test('valid links are escaped and have safe external-link attributes', () => {
  const html = render('[文档](https://example.com/?a=1&b=2)')
  assert.match(html, /href="https:\/\/example.com\/\?a=1&amp;b=2"/)
  assert.match(html, /rel="noopener noreferrer"/)
  assert.match(html, /target="_blank"/)
})

test('images cannot trigger remote fetches and code blocks remain literal', () => {
  const html = render('![图片](https://evil.example/track)\n\n```html\n<img src=x onerror=alert(1)>\n```')
  assert.doesNotMatch(html, /<img\b|src="https:/)
  assert.match(html, /图片/)
  assert.match(html, /&lt;img/)
})

test('assistant rendering is isolated from user message interpolation', () => {
  const view = readFileSync(new URL('../src/views/LoveAppView.vue', import.meta.url), 'utf8')
  assert.match(view, /<AssistantMarkdown v-if="message.content" :content="message.content"/)
  assert.match(view, /<div class="bubble-content">\{\{ message.content \}\}<\/div>/)
  const component = readFileSync(new URL('../src/components/AssistantMarkdown.vue', import.meta.url), 'utf8')
  assert.match(component, /computed\(\(\) => renderAssistantMarkdown\(props.content\)\)/)
})
