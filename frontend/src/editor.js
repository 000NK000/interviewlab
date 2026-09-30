import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution'
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution'

self.MonacoEnvironment = { getWorker() { return new EditorWorker() } }
monaco.editor.defineTheme('interviewlab', {
  base: 'vs-dark', inherit: true,
  rules: [
    { token: 'comment', foreground: '819090' },
    { token: 'keyword', foreground: 'C792EA' },
    { token: 'string', foreground: 'C3E88D' },
    { token: 'number', foreground: 'F78C6C' }
  ],
  colors: { 'editor.background': '#111c24', 'editor.foreground': '#e7f1f0', 'editorLineNumber.foreground': '#5d757e', 'editorCursor.foreground': '#72e8c2', 'editor.selectionBackground': '#1b5d62' }
})
export { monaco }
