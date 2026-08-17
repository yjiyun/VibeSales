/**
 * [P1] 向导话术（人感化 / 专业化）— 规则模板底稿
 *
 * 风格参考：docs/3chat搭建助手.md
 * 有 Key 时由 WizardLlmReceptionist 润色/出题；失败或 --no-llm 时回退本文。
 */

import { WizardDetailSupplement, WizardSummary } from '../common/types';

export const WizardSpeech = {
  welcome(): string {
    return [
      '欢迎使用智能体助手。',
      '我会先通过几个小问题了解你的业务，大概只需要 1 分钟。',
      '完成后，我会帮你搭建第一版Agent智能体，你可以直接预览效果，并继续优化。'
    ].join('\n');
  },

  askIndustry(): string {
    return [
      '你所在的行业是什么？先选一个最接近的，我会据此判断智能体更适合解决哪些问题。',
      '',
      '如果这些选项都不太匹配，也可以直接告诉我你们具体是做什么的。',
    ].join('\n');
  },

  echoIndustry(industryName: string): string {
    return `收到，先按**${industryName}**来理解你的业务。`;
  },

  askBusinessGoals(): string {
    return [
      '你更希望 AI 先帮你处理哪类业务——接待新客、推动转化、答疑，还是识别高意向后转人工？',
      '',
      '这会直接决定智能体更偏接待、转化、答疑，还是转人工分流。（可多选）',
    ].join('\n');
  },

  echoGoals(goalNames: string[]): string {
    if (goalNames.length === 0) {
      return '好的，你也可以稍后再补具体业务目标。';
    }
    return [
      '收到，这样我就知道要优先帮你承接这类咨询场景，',
      '我会按这个方向去搭对话流程和转化重点。',
    ].join('');
  },

  askBusinessBrief(): string {
    return [
      '最后一个问题：简单说说你主要卖什么、或者主要做什么业务就可以。',
      '',
      '建议的描述方式：',
      '  主要业务：……',
      '  服务对象：……',
      '（可留空跳过，直接回车）',
    ].join('\n');
  },

  /** 业务简述的「使用模板」文本底稿（无场景词表数据时兜底）。 */
  briefTemplate(industryName?: string): string {
    const who = industryName ? `${industryName}相关的客户` : '（例如新客 / 老客）';
    return [
      `主要业务：（例如${industryName ? `${industryName}的` : ''}线上咨询与预约）`,
      `服务对象：${who}`,
    ].join('\n');
  },

  /** 细补各项的「使用模板」文本底稿（LLM 未给 example_reply 时兜底）。 */
  detailTemplate(
    key: keyof WizardDetailSupplement,
    industryName?: string,
  ): string {
    const inIndustry = industryName ? `${industryName}` : '我们';
    const map: Record<keyof WizardDetailSupplement, string> = {
      flagship_products: `${inIndustry}最常介绍的是（产品/服务名），主要卖点是（价格带 / 效果 / 服务方式）。`,
      primary_customers: '主要客户是（人群或岗位角色），他们最常关心（价格 / 效果 / 周期）。',
      prohibitions: '不要承诺（疗效 / 绝对化效果 / 未授权折扣），也不要（透露内部政策）。',
      goals: '希望达到的效果是（留下联系方式 / 预约到店 / 完成初筛）。',
      escalate_scenes: '遇到（问具体报价 / 要退款 / 情绪激动投诉 / 明确要人工）时转人工。',
    };
    return map[key];
  },

  /**
   * 用户只表达了「补充信息」意图但没给内容时的引导：
   * 先给出可补字段的文本清单，鼓励用自然语言一次说完。
   */
  detailGuide(keys: Array<keyof WizardDetailSupplement>, industryName: string): string {
    const lines = keys.map((k) => {
      const { title, hint } = WizardSpeech.detailFieldPrompt(k, industryName);
      return `* **${title}**：${hint}`;
    });
    return [
      '可以。你可以补充这些内容：',
      '',
      ...lines,
      '',
      '直接用自然语言一次说完就行（例如「主要客户：有招聘需求的企业；目标：拉群完成候选人初筛」），我会自动归类到对应字段；也可以按我下面的提问一项项来。',
    ].join('\n');
  },

  /** 从自由文本提取到字段后的回显。 */
  detailExtracted(items: Array<{ label: string; value: string }>): string {
    const lines = items.map((it) => `* **${it.label}**：${it.value}`);
    return ['我从你的描述里提取到这些信息：', '', ...lines].join('\n');
  },

  summaryIntro(): string {
    return [
      '🎉 我已经收集到搭建初版智能体的基本信息，先把目前理解到的整理一下：',
    ].join('\n');
  },

  formatSummary(s: WizardSummary): string {
    const goals =
      s.business_goals.length > 0
        ? s.business_goals.map((g) => g.name).join('；')
        : '（待补充）';
    const lines = [
      `* 🏢 **行业**：${s.industry.name}`,
      `* 🎯 **角色定位**：${s.role_positioning}`,
      `* 🛍️ **产品 / 服务**：${s.business_brief?.trim() || '（待补充：主要卖什么 / 服务谁）'}`,
      `* 📈 **目标**：${goals}`,
      `* ⚡ **核心能力**：${s.core_capabilities.join('、') || '（由业务目标推导）'}`,
      `* 🎯 **当前重点**：${s.current_focus}`,
      `* 📚 **配套知识库**：${s.knowledge_packs_planned.join('；')}`,
    ];
    return lines.join('\n');
  },

  summaryCta(): string {
    return [
      '',
      '**接下来你可以这样继续 👇**',
      '1. 🚀 **想先看看效果** —— 我可以先按当前信息进入下一阶段（模板匹配 / 预览）。',
      '2. 📝 **继续补充细节** —— 补主打产品、客户、禁止事项、转人工规则等，会更贴近真实业务。',
      '',
      '请输入 1 或 2（也可输入「先看看效果」/「继续补充细节」）。',
    ].join('\n');
  },

  detailIntro(): string {
    return [
      '可以，这一步补得越具体，首版智能体越容易贴近真实接待流程。',
      '',
      '请优先补充这些关键细节中的任意几项（单项可留空跳过；输入「生成」结束细补）：',
    ].join('\n');
  },

  detailFieldPrompt(
    key: keyof WizardDetailSupplement,
    industryName: string,
  ): { title: string; hint: string } {
    const map: Record<
      keyof WizardDetailSupplement,
      { title: string; hint: string }
    > = {
      flagship_products: {
        title: '主打产品',
        hint: `例如在${industryName}里，你们最常介绍或销售的产品/服务类型是什么？`,
      },
      primary_customers: {
        title: '主要客户',
        hint: '典型客户人群是谁？例如新客、老客、某年龄段或某岗位角色。',
      },
      prohibitions: {
        title: '禁止事项',
        hint: 'AI 不该承诺或涉及哪些事？例如疗效承诺、绝对化保证、未授权折扣等。',
      },
      goals: {
        title: '目标',
        hint: '除已选业务目标外，还有没有更具体的成功标准？（可与前面目标合并理解）',
      },
      escalate_scenes: {
        title: '转人工场景',
        hint: '哪些情况必须尽快转人工？例如问价格、退款、情绪激动投诉、明确要人工。',
      },
    };
    return map[key];
  },

  afterDetail(): string {
    return '关键信息我都记下了。下面是更新后的理解摘要。';
  },

  /** [P2] 进入匹配前的过渡话术：CTA 即生成，不再让用户去点按钮。 */
  previewHandoff(): string {
    return '好的，正在按你的信息匹配可用方案，稍等一下。';
  },

  /**
   * [P2] gate ≠ PASS 却仍进匹配时的一句人话，避免静默白跑。
   * ASK/CUSTOM 都还能跑，只是结果大概率是「没有现成标品」。
   */
  matchGateNotice(gate: string): string {
    if (gate === 'CUSTOM') {
      return '有件事先说明：你的场景暂时没有对应的标品，下面给的是定制轮廓和建议。';
    }
    if (gate === 'ASK') {
      return '有件事先说明：还有信息没补齐，下面的匹配结果只是按现有信息的初判，补全后可以再跑一次。';
    }
    return '有件事先说明：当前信息还没完全达标，下面的结果仅作参考。';
  },

  /** [P2] 匹配过程出错时的降级提示（P1 成果不受影响）。 */
  matchFailed(): string {
    return '匹配没跑通，前面整理好的信息都还在。可以点「重跑匹配」再试一次。';
  },

  /** DONE 后收尾提示：会话仍开着，可自由改写。 */
  doneHint(): string {
    return '信息已整理完成。你还可以直接说要改什么（例如「行业改成医疗健康」），或点「先看看效果」进入预览。';
  },

  /** DONE 后改写：模型不可用 / 解析不出时的单选题开场。 */
  revisePickIntro(): string {
    return '好的。想改哪一项？选一项后我会重新问你。';
  },

  /**
   * 信息收集清单的短标题与「为什么问」。
   * 细补五项优先用 detailPrompts 的标题；这里只提供兜底。
   */
  collectMeta(
    key: string,
  ): { label: string; why: string } {
    const map: Record<string, { label: string; why: string }> = {
      industry: {
        label: '行业',
        why: '决定智能体更适合解决哪类问题、匹配哪类行业模板。',
      },
      business_goals: {
        label: '业务目标',
        why: '决定智能体更偏接待、转化、答疑还是转人工分流。',
      },
      business_brief: {
        label: '业务简述',
        why: '补齐卖什么 / 服务谁，写入总结与角色定位。',
      },
      'detail.flagship_products': {
        label: '主打产品',
        why: '让介绍与推荐更贴近真实货盘。',
      },
      'detail.primary_customers': {
        label: '主要客户',
        why: '决定分流与话术面向的客群。',
      },
      'detail.prohibitions': {
        label: '禁止事项',
        why: '划清 AI 不该承诺或涉及的边界。',
      },
      'detail.goals': {
        label: '目标',
        why: '补更具体的成功标准，可与业务目标合并理解。',
      },
      'detail.escalate_scenes': {
        label: '转人工场景',
        why: '明确哪些情况必须尽快转人工。',
      },
    };
    return (
      map[key] ?? {
        label: key,
        why: '',
      }
    );
  },
};

/** 信息收集主线（必填，计入进度）与细补（可选）的固定 key 顺序。 */
export const COLLECT_CORE_KEYS = [
  'industry',
  'business_goals',
  'business_brief',
] as const;

export const COLLECT_DETAIL_KEYS: Array<keyof WizardDetailSupplement> = [
  'flagship_products',
  'primary_customers',
  'prohibitions',
  'goals',
  'escalate_scenes',
];
