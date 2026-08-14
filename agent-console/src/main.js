import { createApp } from 'vue'; import ElementPlus from 'element-plus'; import 'element-plus/dist/index.css'; import ElementPlusX from 'vue-element-plus-x'; import 'vue-element-plus-x/styles/index.css'; import App from './App.vue'; import './shell.css'; import './wizard/styles.css';
createApp(App).use(ElementPlus).use(ElementPlusX).mount('#app');
