/**
 * 预览页可视化编辑器。
 *
 * <p>编辑脚本只注入到当前 iframe 的内存 DOM，不会修改用户下载的源代码。
 * 为了使用 DOM 注入，预览页必须与工作区同源；通信同时校验 source 和 origin，
 * 不接受其他窗口伪造的元素信息。</p>
 */
export interface ElementInfo {
  tagName: string
  id: string
  className: string
  textContent: string
  selector: string
  pagePath: string
  rect: {
    top: number
    left: number
    width: number
    height: number
  }
}

export interface VisualEditorOptions {
  onElementSelected?: (elementInfo: ElementInfo) => void
  onElementHover?: (elementInfo: ElementInfo) => void
  onError?: (message: string) => void
}

type VisualEditorMessage = {
  namespace?: string
  type?: string
  data?: {
    elementInfo?: ElementInfo
  }
}

const MESSAGE_NAMESPACE = 'lian-ai-visual-editor'
const SCRIPT_ID = 'lian-ai-visual-editor-script'

export class VisualEditor {
  private iframe: HTMLIFrameElement | null = null
  private expectedOrigin = ''
  private editMode = false
  private injectionTimer: number | undefined

  constructor(private readonly options: VisualEditorOptions = {}) {}

  /** 绑定当前预览 iframe；路由或版本切换时应重新绑定。 */
  init(iframe: HTMLIFrameElement) {
    this.iframe = iframe
    try {
      this.expectedOrigin = new URL(iframe.src, window.location.href).origin
    } catch {
      this.expectedOrigin = window.location.origin
    }
  }

  /**
   * 动态注入依赖同源 DOM。跨源时不能通过前端绕过浏览器同源策略，调用方应提示用户改用
   * 同源的 /api 代理地址，而不是降级成不安全的通配目标源。
   */
  canUseSameOrigin() {
    if (!this.iframe || !this.expectedOrigin || this.expectedOrigin !== window.location.origin) {
      return false
    }
    try {
      return Boolean(this.iframe.contentDocument && this.iframe.contentWindow)
    } catch {
      return false
    }
  }

  enableEditMode() {
    if (!this.canUseSameOrigin()) {
      this.options.onError?.('预览页面与工作区不同源，无法开启可视化编辑')
      return false
    }
    this.editMode = true
    this.scheduleInjection()
    return true
  }

  disableEditMode() {
    this.editMode = false
    this.clearInjectionTimer()
    this.sendMessage({ type: 'TOGGLE_EDIT_MODE', editMode: false })
    this.sendMessage({ type: 'CLEAR_ALL_EFFECTS' })
  }

  toggleEditMode() {
    if (this.editMode) {
      this.disableEditMode()
      return false
    }
    return this.enableEditMode()
  }

  clearSelection() {
    this.sendMessage({ type: 'CLEAR_SELECTION' })
  }

  /** iframe 重新加载后调用；编辑脚本只存在于当前文档，需要再次注入。 */
  onIframeLoad() {
    if (this.editMode) {
      this.scheduleInjection()
    } else {
      this.sendMessage({ type: 'CLEAR_ALL_EFFECTS' })
    }
  }

  handleIframeMessage(event: MessageEvent<VisualEditorMessage>) {
    if (!this.isTrustedMessage(event)) {
      return
    }
    // 关闭编辑模式后，浏览器事件队列中可能仍有迟到消息；不能把旧目标带入下一轮提示词。
    if (!this.editMode) {
      return
    }
    const message = event.data
    if (message.type === 'ELEMENT_SELECTED' && message.data?.elementInfo
      && isElementInfo(message.data.elementInfo)) {
      this.options.onElementSelected?.(message.data.elementInfo)
    }
    if (message.type === 'ELEMENT_HOVER' && message.data?.elementInfo
      && isElementInfo(message.data.elementInfo)) {
      this.options.onElementHover?.(message.data.elementInfo)
    }
  }

  /** 组件卸载或路由切换时释放定时器和 iframe 引用。 */
  dispose() {
    this.disableEditMode()
    this.iframe = null
    this.expectedOrigin = ''
  }

  private scheduleInjection() {
    this.clearInjectionTimer()
    this.injectionTimer = window.setTimeout(() => {
      this.injectionTimer = undefined
      this.injectEditScript()
    }, 50)
  }

  private clearInjectionTimer() {
    if (this.injectionTimer !== undefined) {
      window.clearTimeout(this.injectionTimer)
      this.injectionTimer = undefined
    }
  }

  private isTrustedMessage(event: MessageEvent<VisualEditorMessage>) {
    return Boolean(
      this.iframe
      && event.source === this.iframe.contentWindow
      && event.origin === this.expectedOrigin
      && event.data?.namespace === MESSAGE_NAMESPACE,
    )
  }

  private sendMessage(message: Record<string, unknown>) {
    if (!this.iframe?.contentWindow || !this.expectedOrigin) {
      return
    }
    if (!this.canUseSameOrigin()) {
      return
    }
    this.iframe.contentWindow.postMessage({ namespace: MESSAGE_NAMESPACE, ...message }, this.expectedOrigin)
  }

  private injectEditScript() {
    if (!this.iframe || !this.editMode || !this.canUseSameOrigin()) {
      return
    }
    try {
      const document = this.iframe.contentDocument
      if (!document?.head) {
        this.scheduleInjection()
        return
      }
      const existingScript = document.getElementById(SCRIPT_ID)
      if (existingScript) {
        this.sendMessage({ type: 'TOGGLE_EDIT_MODE', editMode: true })
        return
      }
      const script = document.createElement('script')
      script.id = SCRIPT_ID
      script.textContent = this.generateEditScript()
      document.head.appendChild(script)
    } catch {
      this.options.onError?.('预览页面暂时无法开启可视化编辑，请确认前端使用同源 /api 地址')
    }
  }

  private generateEditScript() {
    // 这里的脚本只运行在预览 iframe 的临时 DOM 中；所有用户输入仅作为消息数据传回父页。
    return `
      (function () {
        var namespace = '${MESSAGE_NAMESPACE}';
        var hoverClass = 'ai-visual-hover';
        var selectedClass = 'ai-visual-selected';
        var currentHover = null;
        var currentSelected = null;
        var editMode = true;

        function isIgnorable(element) {
          if (!(element instanceof Element)) return true;
          var tag = element.tagName.toLowerCase();
          return tag === 'html' || tag === 'body' || tag === 'script' || tag === 'style'
            || tag === 'link' || tag === 'meta';
        }

        function cssEscape(value) {
          if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(value);
          return String(value).replace(/[^a-zA-Z0-9_-]/g, '\\\\$&');
        }

        function selectorFor(element) {
          var path = [];
          var current = element;
          while (current && current instanceof Element && current !== document.body) {
            var selector = current.tagName.toLowerCase();
            if (current.id) {
              selector += '#' + cssEscape(current.id);
              path.unshift(selector);
              break;
            }
            var className = typeof current.className === 'string' ? current.className : current.getAttribute('class') || '';
            var classes = className.split(/\\s+/).filter(function (name) {
              return name && name.indexOf('ai-visual-') !== 0;
            }).slice(0, 3);
            classes.forEach(function (name) { selector += '.' + cssEscape(name); });
            var parent = current.parentElement;
            if (parent) {
              var siblings = Array.prototype.filter.call(parent.children, function (item) {
                return item.tagName === current.tagName;
              });
              if (siblings.length > 1) selector += ':nth-of-type(' + (siblings.indexOf(current) + 1) + ')';
            }
            path.unshift(selector);
            current = parent;
          }
          return path.join(' > ');
        }

        function elementInfo(element) {
          var rect = element.getBoundingClientRect();
          var rawClassName = typeof element.className === 'string'
            ? element.className : element.getAttribute('class') || '';
          // 编辑器注入的临时高亮类不属于页面本身，不能混进发给后端的定位信息。
          var className = rawClassName.split(/\\s+/).filter(function (name) {
            return name && name.indexOf('ai-visual-') !== 0;
          }).join(' ');
          return {
            tagName: element.tagName,
            id: String(element.id || '').slice(0, 120),
            className: String(className).slice(0, 240),
            textContent: String(element.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 160),
            selector: selectorFor(element).slice(0, 500),
            pagePath: window.location.pathname + window.location.search + window.location.hash,
            rect: { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
          };
        }

        function send(type, info) {
          window.parent.postMessage({ namespace: namespace, type: type, data: { elementInfo: info } }, window.location.origin);
        }

        function clearHover() {
          if (currentHover) currentHover.classList.remove(hoverClass);
          currentHover = null;
        }

        function clearSelected() {
          document.querySelectorAll('.' + selectedClass).forEach(function (element) {
            element.classList.remove(selectedClass);
          });
          currentSelected = null;
        }

        function injectStyles() {
          if (document.getElementById('ai-visual-editor-styles')) return;
          var style = document.createElement('style');
          style.id = 'ai-visual-editor-styles';
          style.textContent = '.ai-visual-hover { outline: 2px dashed #1677ff !important; outline-offset: 2px !important; cursor: crosshair !important; }'
            + '.ai-visual-selected { outline: 3px solid #52c41a !important; outline-offset: 2px !important; cursor: crosshair !important; }';
          document.head.appendChild(style);
        }

        function setEditMode(enabled) {
          editMode = Boolean(enabled);
          if (editMode) {
            injectStyles();
          } else {
            clearHover();
            clearSelected();
          }
        }

        document.addEventListener('mouseover', function (event) {
          if (!editMode || isIgnorable(event.target)) return;
          var target = event.target;
          if (target === currentSelected || target === currentHover) return;
          clearHover();
          target.classList.add(hoverClass);
          currentHover = target;
          send('ELEMENT_HOVER', elementInfo(target));
        }, true);

        document.addEventListener('mouseout', function (event) {
          if (!editMode || !currentHover) return;
          var related = event.relatedTarget;
          if (!(related instanceof Node) || !currentHover.contains(related)) clearHover();
        }, true);

        document.addEventListener('click', function (event) {
          if (!editMode || isIgnorable(event.target)) return;
          event.preventDefault();
          event.stopPropagation();
          var target = event.target;
          clearHover();
          clearSelected();
          target.classList.add(selectedClass);
          currentSelected = target;
          send('ELEMENT_SELECTED', elementInfo(target));
        }, true);

        window.addEventListener('message', function (event) {
          if (event.source !== window.parent || event.origin !== window.location.origin) return;
          var data = event.data || {};
          if (data.namespace !== namespace) return;
          if (data.type === 'TOGGLE_EDIT_MODE') setEditMode(data.editMode);
          if (data.type === 'CLEAR_SELECTION') clearSelected();
          if (data.type === 'CLEAR_ALL_EFFECTS') setEditMode(false);
        });

        window.__lianAiVisualEditor = { setEditMode: setEditMode };
        injectStyles();
      })();
    `
  }
}

function isElementInfo(value: unknown): value is ElementInfo {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<ElementInfo>
  const rect = candidate.rect
  if (!rect || typeof rect !== 'object') return false
  const rectValue = rect as ElementInfo['rect']
  return boundedString(candidate.tagName, 40)
    && boundedString(candidate.selector, 500)
    && boundedString(candidate.pagePath, 240)
    && boundedString(candidate.textContent, 160)
    && boundedString(candidate.className, 240)
    && boundedString(candidate.id, 120)
    && Number.isFinite(rectValue.top)
    && Number.isFinite(rectValue.left)
    && Number.isFinite(rectValue.width)
    && Number.isFinite(rectValue.height)
    && rectValue.width >= 0
    && rectValue.height >= 0
}

function boundedString(value: unknown, maxLength: number): value is string {
  return typeof value === 'string' && value.length <= maxLength
}
