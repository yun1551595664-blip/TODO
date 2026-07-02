import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, RequireAuth, RequirePermission } from "./auth";
import AppLayout from "./components/AppLayout";
import Dashboard from "./pages/Dashboard";
import IssueList from "./pages/IssueList";
import IssueForm from "./pages/IssueForm";
import IssueDetail from "./pages/IssueDetail";
import DataReport from "./pages/DataReport";
import AiInsightPage from "./pages/AiInsightPage";
import FieldSettings from "./pages/FieldSettings";
import AccountSettings from "./pages/AccountSettings";
import RetrospectivePage from "./pages/RetrospectivePage";
import LoginPage from "./pages/LoginPage";
export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="login" element={<LoginPage />} />
        <Route
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route index element={<Dashboard />} />
          <Route path="issues" element={<IssueList />} />
          <Route path="issues/new" element={<IssueForm />} />
          <Route path="issues/:id/edit" element={<IssueForm />} />
          <Route path="issues/:id" element={<IssueDetail />} />
          <Route path="ai-insights" element={<AiInsightPage />} />
          <Route path="data" element={<DataReport />} />
          <Route path="retrospective" element={<RetrospectivePage />} />
          <Route
            path="settings/fields"
            element={
              <RequirePermission permission="field:manage">
                <FieldSettings />
              </RequirePermission>
            }
          />
          <Route
            path="settings/accounts"
            element={
              <RequirePermission permission="account:manage">
                <AccountSettings />
              </RequirePermission>
            }
          />
          <Route
            path="knowledge"
            element={<Navigate to="/retrospective" replace />}
          />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  );
}
