import { h, type VNode } from 'snabbdom';
import * as licon from '../licon';
import { bind } from '../snabbdom';
import type { Tab, Palantir } from './interfaces';
import discussionView from './discussion';
import { noteView } from './note';
import { moderationView } from './moderation';

import type ChatCtrl from './ctrl';

export default function (ctrl: ChatCtrl): VNode {
  return h(
    'section.mchat' + (ctrl.opts.alwaysEnabled ? '' : '.mchat-optional'),
    { class: { 'mchat-mod': !!ctrl.moderation }, hook: { destroy: ctrl.destroy } },
    moderationView(ctrl.moderation) || normalView(ctrl),
  );
}

function renderPalantir(ctrl: ChatCtrl) {
  const p = ctrl.palantir;
  if (!p.enabled()) return;
  return p.instance
    ? p.instance.render()
    : h('div.mchat__tab.palantir.palantir-slot', {
        attrs: { 'data-icon': licon.Handset, title: 'Voice chat' },
        hook: bind('click', () => {
          if (!p.loaded) {
            p.loaded = true;
            site.asset
              .loadEsm<Palantir>('palantir', {
                init: { uid: ctrl.data.userId!, redraw: ctrl.redraw },
              })
              .then(m => {
                p.instance = m;
                ctrl.redraw();
              });
          }
        }),
      });
}

function normalView(ctrl: ChatCtrl) {
  const active = ctrl.vm.tab;
  return [
    h('div.mchat__tabs.nb_' + ctrl.allTabs.length, { attrs: { role: 'tablist' } }, [
      ...ctrl.allTabs.map(t => renderTab(ctrl, t, active)),
      renderPalantir(ctrl),
    ]),
    h(
      'div.mchat__content.' + active,
      active === 'note' && ctrl.note
        ? [noteView(ctrl.note, ctrl.vm.autofocus)]
        : ctrl.plugin && active === ctrl.plugin.tab.key
          ? [ctrl.plugin.view()]
          : discussionView(ctrl),
    ),
  ];
}

const renderTab = (ctrl: ChatCtrl, tab: Tab, active: Tab) =>
  h(
    'div.mchat__tab.' + tab,
    {
      attrs: { role: 'tab' },
      class: { 'mchat__tab-active': tab === active },
      hook: bind('click', () => ctrl.setTab(tab)),
    },
    tabName(ctrl, tab),
  );

function tabName(ctrl: ChatCtrl, tab: Tab) {
  if (tab === 'discussion') {
    const id = `chat-toggle-${ctrl.data.id}`;
    return [
      h('span', ctrl.data.name),
      ctrl.opts.alwaysEnabled
        ? undefined
        : h('div.switch', [
            h(`input#${id}.cmn-toggle.cmn-toggle--subtle`, {
              attrs: {
                type: 'checkbox',
                checked: ctrl.vm.enabled,
              },
              hook: bind('change', e => {
                ctrl.setEnabled((e.target as HTMLInputElement).checked);
              }),
            }),
            h('label', {
              attrs: {
                for: id,
                title: i18n.site.toggleTheChat,
              },
            }),
          ]),
    ];
  }
  if (tab === 'note') return [h('span', i18n.site.notes)];
  if (ctrl.plugin && tab === ctrl.plugin.tab.key) return [h('span', ctrl.plugin.tab.name)];
  return [];
}
