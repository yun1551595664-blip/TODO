import { Navigate, Route, Routes } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import Dashboard from "./pages/Dashboard";
import IssueList from "./pages/IssueList";
import IssueForm from "./pages/IssueForm";
import IssueDetail from "./pages/IssueDetail";
import DataReport from "./pages/DataReport";
import AiInsightPage from "./pages/AiInsightPage";
import FieldSettings from "./pages/FieldSettings";
import RetrospectivePage from "./pages/RetrospectivePage";
export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Dashboard />} />
        <Route path="issues" element={<IssueList />} />
        <Route path="issues/new" element={<IssueForm />} />
        <Route path="issues/:id/edit" element={<IssueForm />} />
        <Route path="issues/:id" element={<IssueDetail />} />
        <Route path="ai-insights" element={<AiInsightPage />} />
        <Route path="data" element={<DataReport />} />
        <Route path="retrospective" element={<RetrospectivePage />} />
        <Route path="settings/fields" element={<FieldSettings />} />
        <Route path="knowledge" element={<Navigate to="/retrospective" replace />} />
      </Route>
    </Routes>
  );
}
