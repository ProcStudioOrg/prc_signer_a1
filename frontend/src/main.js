import { mount } from 'svelte'
import './app.css'
import App from './App.svelte'

// O bloco #seo-prerender no index.html existe só para o HTML servido não
// chegar vazio ao crawler. Ele repete o hero do App.svelte, então sai de cena
// assim que o app real monta — senão o texto apareceria duas vezes.
document.getElementById('seo-prerender')?.remove()

const app = mount(App, {
  target: document.getElementById('app'),
})

export default app
