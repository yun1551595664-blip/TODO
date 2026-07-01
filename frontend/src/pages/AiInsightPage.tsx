import AiInsightCommandCenter from "../components/AiInsightCommandCenter";

export default function AiInsightPage() {
  return (
    <div className="page ai-insight-page">
      <div className="page-heading ai-insight-page-heading">
        <div>
          <h1>AI 洞察</h1>
          <p>基于问题数据的风险研判、优先级排序与行动建议</p>
        </div>
      </div>
      <AiInsightCommandCenter />
    </div>
  );
}
