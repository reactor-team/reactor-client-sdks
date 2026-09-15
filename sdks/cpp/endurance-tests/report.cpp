#include "report.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <ios>
#include <iomanip>
#include <iostream>
#include <memory>
#include <nlohmann/json.hpp>
#include <sstream>
#include <stdexcept>

namespace endurance {

const std::string kDefaultOutputDir = "sdks/cpp/endurance-results";

namespace {

std::string env_or(const char* name, std::string fallback) {
  const char* value = std::getenv(name);
  return value != nullptr ? std::string{value} : std::move(fallback);
}

bool env_flag(const char* name) {
  const std::string value = env_or(name, "");
  return value == "1" || value == "true" || value == "TRUE" || value == "True";
}

std::string format_value(double value, const std::string& unit) {
  std::ostringstream oss;
  if (unit == "MB") {
    oss.precision(1);
    oss << std::fixed << value << " MB";
  } else if (unit == "%") {
    oss.precision(1);
    oss << std::fixed << value << "%";
  } else if (unit == "count") {
    oss.precision(0);
    oss << std::fixed << value;
  } else if (unit == "s") {
    oss.precision(3);
    oss << std::fixed << value << "s";
  } else {
    oss.precision(3);
    oss << std::fixed << value;
  }
  return oss.str();
}

std::string format_delta(double value, const std::string& unit) {
  const std::string sign = value >= 0 ? "+" : "";
  return sign + format_value(value, unit);
}

}  // namespace

bool endurance_verbose() { return env_flag("ENDURANCE_VERBOSE"); }

double live_interval_seconds() {
  const char* raw = std::getenv("ENDURANCE_LIVE_INTERVAL_SECONDS");
  return raw != nullptr ? std::stod(raw) : 30.0;
}

std::string now_iso() {
  const auto now = std::chrono::system_clock::now();
  const std::time_t now_c = std::chrono::system_clock::to_time_t(now);
  std::tm tm_utc{};
#if defined(_WIN32)
  gmtime_s(&tm_utc, &now_c);
#else
  gmtime_r(&now_c, &tm_utc);
#endif
  std::ostringstream oss;
  oss << std::put_time(&tm_utc, "%Y-%m-%dT%H:%M:%S") << "+00:00";
  return oss.str();
}

std::optional<std::string> git_commit_sha() {
  const char* sha = std::getenv("GITHUB_SHA");
  if (sha != nullptr && sha[0] != '\0') {
    return std::string{sha};
  }
  // Best-effort `git rev-parse HEAD` for a local run — never throws; a
  // missing commit SHA is fine to just omit.
  std::array<char, 128> buffer{};
  std::string result;
  FILE* pipe = popen("git rev-parse HEAD 2>/dev/null", "r");  // NOLINT
  if (pipe == nullptr) {
    return std::nullopt;
  }
  while (std::fgets(buffer.data(), static_cast<int>(buffer.size()), pipe) != nullptr) {
    result += buffer.data();
  }
  pclose(pipe);
  while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
    result.pop_back();
  }
  if (result.empty()) {
    return std::nullopt;
  }
  return result;
}

std::string format_duration(double seconds) {
  auto total = static_cast<long long>(std::max(0.0, seconds));
  const long long h = total / 3600;
  const long long m = (total % 3600) / 60;
  const long long s = total % 60;
  std::ostringstream oss;
  if (h != 0) {
    oss << h << "h " << std::setfill('0') << std::setw(2) << m << "m";
  } else if (m != 0) {
    oss << m << "m " << std::setfill('0') << std::setw(2) << s << "s";
  } else {
    oss << s << "s";
  }
  return oss.str();
}

std::string MetricResult::row_markdown() const {
  const std::string mid_str = mid.has_value() ? format_value(*mid, unit) : "—";
  const auto mc = mid_change();
  const std::string mid_change_str = mc.has_value() ? format_delta(*mc, unit) : "—";
  std::ostringstream oss;
  oss << "| " << name << " | " << format_value(start, unit) << " | " << mid_str << " | "
      << format_value(end, unit) << " | " << mid_change_str << " | " << emoji() << " "
      << status_label() << " |";
  return oss.str();
}

std::string MetricResult::row_text() const {
  const std::string mid_str = mid.has_value() ? format_value(*mid, unit) : "—";
  const std::string end_str = format_value(end, unit);
  const auto mc = mid_change();
  const std::string mid_change_str = mc.has_value() ? format_delta(*mc, unit) : "—";
  std::ostringstream oss;
  oss << "  " << std::left << std::setw(20) << name << std::right << std::setw(12)
      << format_value(start, unit) << " -> " << std::setw(12) << mid_str << " -> " << std::left
      << std::setw(12) << end_str << std::right << std::setw(10) << mid_change_str << "  "
      << emoji() << " " << status_label();
  return oss.str();
}

std::string RunResult::emoji() const {
  if (status == "PASS") {
    return "\U0001F7E2";
  }
  if (status == "FAIL") {
    return "\U0001F534";
  }
  if (status == "ERROR") {
    return "\U0001F7E0";
  }
  return "⚪";
}

std::vector<Checkpoint> compute_checkpoints(const std::vector<Sample>& samples, int n) {
  std::vector<Checkpoint> checkpoints;
  if (samples.empty()) {
    return checkpoints;
  }
  const std::size_t last_idx = samples.size() - 1;
  for (int i = 0; i < n; ++i) {
    const double pct = (n > 1) ? (static_cast<double>(i) / static_cast<double>(n - 1)) : 0.0;
    const auto idx = static_cast<std::size_t>(std::lround(pct * static_cast<double>(last_idx)));
    const Sample& s = samples.at(idx);
    Checkpoint c;
    c.pct = static_cast<int>(std::lround(pct * 100));
    c.cycle = s.cycle;
    c.elapsed_s = s.elapsed_s;
    c.rss_mb = static_cast<double>(s.rss_bytes) / 1e6;
    c.num_threads = s.num_threads;
    c.num_fds = s.num_fds;
    c.cpu_percent = s.cpu_percent;
    checkpoints.push_back(c);
  }
  return checkpoints;
}

namespace {

struct TimelineRow {
  std::string at;
  std::string rss;
  std::string threads;
  std::string fds;
  std::string cpu;
};

std::vector<TimelineRow> timeline_rows(const std::vector<Checkpoint>& checkpoints) {
  std::vector<TimelineRow> rows;
  for (const auto& c : checkpoints) {
    TimelineRow row;
    std::ostringstream at;
    at << c.pct << "%";
    if (c.elapsed_s.has_value()) {
      at << " (" << format_duration(*c.elapsed_s) << ")";
    }
    row.at = at.str();
    if (c.rss_mb.has_value()) {
      std::ostringstream oss;
      oss.precision(1);
      oss << std::fixed << *c.rss_mb << " MB";
      row.rss = oss.str();
    } else {
      row.rss = "—";
    }
    row.threads = c.num_threads.has_value() ? std::to_string(*c.num_threads) : "—";
    row.fds = c.num_fds.has_value() ? std::to_string(*c.num_fds) : "—";
    if (c.cpu_percent.has_value()) {
      std::ostringstream oss;
      oss.precision(1);
      oss << std::fixed << *c.cpu_percent << "%";
      row.cpu = oss.str();
    } else {
      row.cpu = "—";
    }
    rows.push_back(row);
  }
  return rows;
}

std::vector<std::string> conclusion_lines(const RunResult& result) {
  std::vector<std::string> lines;
  if (result.status == "ERROR") {
    lines.push_back("The test did not complete: " +
                    result.run_error.value_or("an unexpected error occurred") + ".");
    lines.push_back(
        "This looks like a test error or an infrastructure/setup problem, not a "
        "detected leak — no metric ran to completion to judge.");
    lines.push_back("See the downloadable JSON and logs for details.");
    return lines;
  }

  std::string tested;
  for (std::size_t i = 0; i < result.metrics.size(); ++i) {
    if (i != 0) {
      tested += ", ";
    }
    tested += result.metrics[i].name;
  }
  if (result.metrics.empty()) {
    tested = "no metrics";
  }

  if (result.status == "PASS") {
    lines.push_back("The " + result.sdk + " SDK completed " + format_duration(result.elapsed_s) +
                    " of endurance testing across " + std::to_string(result.iterations) +
                    " iterations without detecting resource leaks or sustained growth in the "
                    "metrics tested (" +
                    tested + ").");
    for (const auto& m : result.metrics) {
      if (m.unit == "count" && m.change == 0) {
        lines.push_back(m.name + " stayed constant at " + format_value(m.end, m.unit) + ".");
      } else {
        lines.push_back(m.name + " changed by " + format_delta(m.change, m.unit) + " (" +
                        format_value(m.start, m.unit) + " → " + format_value(m.end, m.unit) +
                        ") and remained stable.");
      }
    }
    if (result.errors.has_value()) {
      if (*result.errors == 0) {
        lines.push_back("No test errors occurred.");
      } else {
        lines.push_back(std::to_string(*result.errors) +
                        " transient error(s) occurred but did not affect the result.");
      }
    }
    return lines;
  }

  // FAIL
  std::vector<const MetricResult*> failed;
  for (const auto& m : result.metrics) {
    if (m.status == "fail") {
      failed.push_back(&m);
    }
  }
  if (!failed.empty()) {
    std::string names;
    for (std::size_t i = 0; i < failed.size(); ++i) {
      if (i != 0) {
        names += ", ";
      }
      names += failed[i]->name;
    }
    lines.push_back("The endurance test detected a failure in: " + names + ".");
    for (const auto* m : failed) {
      std::string threshold_str;
      if (m->threshold.has_value()) {
        threshold_str = ", exceeding the configured threshold (" + *m->threshold + ")";
      }
      std::string cycle_str;
      if (m->first_bad_cycle.has_value()) {
        cycle_str = " First detected at cycle " + std::to_string(*m->first_bad_cycle) + ".";
      }
      lines.push_back(m->name + " went from " + format_value(m->start, m->unit) + " to " +
                      format_value(m->end, m->unit) + " (" + format_delta(m->change, m->unit) +
                      ")" + threshold_str + "." + cycle_str);
    }
  } else {
    lines.push_back(
        "The endurance test failed without a specific metric crossing its threshold — "
        "likely a test error or infrastructure/setup failure rather than a detected leak.");
  }
  lines.push_back("See the downloadable JSON and diagnostic artifacts for detailed samples.");
  return lines;
}

std::vector<Checkpoint> checkpoints_for(const RunResult& result) {
  if (result.checkpoints.has_value()) {
    return *result.checkpoints;
  }
  return compute_checkpoints(result.samples);
}

}  // namespace

std::string render_markdown(const RunResult& result) {
  std::ostringstream out;
  out << "# " << result.emoji() << " Endurance Test Report — " << result.test_name << "\n\n";
  if (result.description.has_value()) {
    out << *result.description << "\n\n";
  }
  out << result.sdk << " SDK · " << format_duration(result.elapsed_s) << " · " << result.iterations
      << " iterations · **" << result.status << "**\n\n";
  if (result.sdk_version.has_value()) {
    out << "- **SDK version:** `" << *result.sdk_version << "`\n";
  }
  if (result.commit_sha.has_value()) {
    out << "- **Commit:** `" << result.commit_sha->substr(0, 12) << "`\n";
  }
  out << "- **Started:** " << result.started_at << "\n";
  out << "- **Ended:** " << result.ended_at << "\n";
  out << "- **Target duration:** " << format_duration(result.duration_s) << "\n\n";

  if (!result.metrics.empty()) {
    out << "## Results\n\n";
    out << "| Metric | Start | Mid | End | Δ (Mid→End) | Status |\n";
    out << "|---|---:|---:|---:|---:|---|\n";
    for (const auto& m : result.metrics) {
      out << m.row_markdown() << "\n";
    }
    if (result.errors.has_value()) {
      out << "| Errors | " << *result.errors << " | — | " << *result.errors << " | — | "
          << (*result.errors == 0 ? "\U0001F7E2" : "\U0001F7E1") << " |\n";
    }
    out << "\n";
  }

  const auto checkpoints = checkpoints_for(result);
  if (!checkpoints.empty()) {
    out << "## Timeline\n\n";
    out << "| At | RSS | Threads | FDs | CPU% |\n";
    out << "|---|---:|---:|---:|---:|\n";
    for (const auto& row : timeline_rows(checkpoints)) {
      out << "| " << row.at << " | " << row.rss << " | " << row.threads << " | " << row.fds << " | "
          << row.cpu << " |\n";
    }
    out << "\n";
  }

  out << "## Conclusion\n\n";
  out << result.emoji() << " " << result.status << "\n\n";
  for (const auto& line : conclusion_lines(result)) {
    out << line << "\n";
  }
  out << "\n";

  out << "_Detailed samples are available as a downloadable JSON workflow artifact._\n";
  return out.str();
}

std::string render_text(const RunResult& result) {
  std::ostringstream out;
  out << "Endurance Test Report — " << result.test_name << "\n";
  if (result.description.has_value()) {
    out << *result.description << "\n";
  }
  out << result.sdk << " SDK\n";
  out << result.status << "\n";
  out << "Duration: " << format_duration(result.elapsed_s) << "\n\n";
  if (result.sdk_version.has_value()) {
    out << "SDK version:    " << *result.sdk_version << "\n";
  }
  if (result.commit_sha.has_value()) {
    out << "Commit:         " << result.commit_sha->substr(0, 12) << "\n";
  }
  out << "Started:        " << result.started_at << "\n";
  out << "Ended:          " << result.ended_at << "\n";
  out << "Target duration: " << format_duration(result.duration_s) << "\n";
  out << "Iterations:     " << result.iterations << "\n\n";

  if (!result.metrics.empty()) {
    out << "Results\n";
    out << "-------\n";
    for (const auto& m : result.metrics) {
      out << m.row_text() << "\n";
    }
    if (result.errors.has_value()) {
      std::ostringstream row;
      row << "  " << std::left << std::setw(20) << "Errors" << std::right << std::setw(12)
          << *result.errors << " " << std::setw(12) << "" << std::left << std::setw(12) << ""
          << std::right << std::setw(10) << "" << "  "
          << (*result.errors == 0 ? "\U0001F7E2" : "\U0001F7E1");
      out << row.str() << "\n";
    }
    out << "\n";
  }

  const auto checkpoints = checkpoints_for(result);
  if (!checkpoints.empty()) {
    out << "Timeline\n";
    out << "--------\n";
    for (const auto& row : timeline_rows(checkpoints)) {
      out << "  " << std::left << std::setw(14) << row.at << std::right << "RSS " << std::setw(10)
          << row.rss << "  threads " << std::setw(4) << row.threads << "  fds " << std::setw(4)
          << row.fds << "  cpu " << std::setw(6) << row.cpu << "\n";
    }
    out << "\n";
  }

  out << "Conclusion\n";
  out << "----------\n";
  out << result.status << "\n\n";
  for (const auto& line : conclusion_lines(result)) {
    out << line << "\n";
  }
  out << "\n";

  out << "Detailed samples are available as a downloadable JSON workflow artifact.\n";
  return out.str();
}

namespace {

nlohmann::json sample_to_json(const Sample& s) {
  return nlohmann::json{
      {"cycle", s.cycle},    {"elapsed_s", s.elapsed_s},     {"rss_bytes", s.rss_bytes},
      {"cpu_s", s.cpu_s},    {"cpu_percent", s.cpu_percent}, {"num_threads", s.num_threads},
      {"num_fds", s.num_fds}};
}

nlohmann::json checkpoint_to_json(const Checkpoint& c) {
  nlohmann::json j;
  j["pct"] = c.pct;
  j["cycle"] = c.cycle.has_value() ? nlohmann::json(*c.cycle) : nlohmann::json(nullptr);
  j["elapsed_s"] = c.elapsed_s.has_value() ? nlohmann::json(*c.elapsed_s) : nlohmann::json(nullptr);
  j["rss_mb"] = c.rss_mb.has_value() ? nlohmann::json(*c.rss_mb) : nlohmann::json(nullptr);
  j["num_threads"] =
      c.num_threads.has_value() ? nlohmann::json(*c.num_threads) : nlohmann::json(nullptr);
  j["num_fds"] = c.num_fds.has_value() ? nlohmann::json(*c.num_fds) : nlohmann::json(nullptr);
  j["cpu_percent"] =
      c.cpu_percent.has_value() ? nlohmann::json(*c.cpu_percent) : nlohmann::json(nullptr);
  return j;
}

nlohmann::json metric_to_json(const MetricResult& m) {
  nlohmann::json j;
  j["name"] = m.name;
  j["start"] = m.start;
  j["end"] = m.end;
  j["change"] = m.change;
  j["unit"] = m.unit;
  j["status"] = m.status;
  j["detail"] = m.detail;
  j["threshold"] = m.threshold.has_value() ? nlohmann::json(*m.threshold) : nlohmann::json(nullptr);
  j["first_bad_cycle"] =
      m.first_bad_cycle.has_value() ? nlohmann::json(*m.first_bad_cycle) : nlohmann::json(nullptr);
  j["mid"] = m.mid.has_value() ? nlohmann::json(*m.mid) : nlohmann::json(nullptr);
  return j;
}

nlohmann::json run_result_to_json(const RunResult& r) {
  nlohmann::json j;
  j["test_name"] = r.test_name;
  j["sdk"] = r.sdk;
  j["status"] = r.status;
  j["duration_s"] = r.duration_s;
  j["elapsed_s"] = r.elapsed_s;
  j["iterations"] = r.iterations;
  j["started_at"] = r.started_at;
  j["ended_at"] = r.ended_at;
  nlohmann::json metrics = nlohmann::json::array();
  for (const auto& m : r.metrics) {
    metrics.push_back(metric_to_json(m));
  }
  j["metrics"] = metrics;
  j["description"] =
      r.description.has_value() ? nlohmann::json(*r.description) : nlohmann::json(nullptr);
  j["errors"] = r.errors.has_value() ? nlohmann::json(*r.errors) : nlohmann::json(nullptr);
  j["sdk_version"] =
      r.sdk_version.has_value() ? nlohmann::json(*r.sdk_version) : nlohmann::json(nullptr);
  j["commit_sha"] =
      r.commit_sha.has_value() ? nlohmann::json(*r.commit_sha) : nlohmann::json(nullptr);
  j["run_error"] = r.run_error.has_value() ? nlohmann::json(*r.run_error) : nlohmann::json(nullptr);
  nlohmann::json checkpoints = nlohmann::json::array();
  if (r.checkpoints.has_value()) {
    for (const auto& c : *r.checkpoints) {
      checkpoints.push_back(checkpoint_to_json(c));
    }
  }
  j["checkpoints"] = checkpoints;
  nlohmann::json samples = nlohmann::json::array();
  for (const auto& s : r.samples) {
    samples.push_back(sample_to_json(s));
  }
  j["samples"] = samples;
  return j;
}

}  // namespace

ReportPaths write_reports(RunResult& result, const std::string& out_dir) {
  if (!result.checkpoints.has_value() && !result.samples.empty()) {
    result.checkpoints = compute_checkpoints(result.samples);
  }
  std::filesystem::create_directories(out_dir);
  ReportPaths paths;
  paths.json = out_dir + "/" + result.test_name + ".json";
  paths.markdown = out_dir + "/" + result.test_name + "-report.md";
  paths.text = out_dir + "/" + result.test_name + "-summary.txt";

  // ofstream never throws on its own — a failed open, a disk-full write, or
  // any other stream error just sets failbit/badbit and finish_run()'s own
  // catch around write_reports() (see below) would never see it, silently
  // leaving a missing or truncated report where it thinks one was written.
  // Enabling exceptions turns any of those into the same std::ios_base::
  // failure path finish_run() already reports through.
  {
    std::ofstream f(paths.json);
    f.exceptions(std::ios::failbit | std::ios::badbit);
    f << run_result_to_json(result).dump(2);
  }
  {
    std::ofstream f(paths.markdown);
    f.exceptions(std::ios::failbit | std::ios::badbit);
    f << render_markdown(result);
  }
  {
    std::ofstream f(paths.text);
    f.exceptions(std::ios::failbit | std::ios::badbit);
    f << render_text(result);
  }
  return paths;
}

RunResult finish_run(FinishRunArgs args) {
  std::optional<std::string> run_error;
  if (args.run_error) {
    try {
      std::rethrow_exception(args.run_error);
    } catch (const std::exception& e) {
      // e.what() alone, not a demangled type name: unlike Python's
      // `f"{exc_type.__name__}: {exc_val}"`, C++'s typeid(e).name() is
      // compiler-mangled and needs a platform-specific demangler to read —
      // not worth it for a diagnostic string a human reads once.
      run_error = std::string{e.what()};
    } catch (...) {
      run_error = "unknown exception";
    }
  }
  bool any_failed = false;
  for (const auto& m : args.metrics) {
    if (m.status == "fail") {
      any_failed = true;
      break;
    }
  }
  std::string status = run_error.has_value() ? "ERROR" : (any_failed ? "FAIL" : "PASS");

  RunResult result;
  result.test_name = args.test_name;
  result.sdk = args.sdk;
  result.status = status;
  result.duration_s = args.duration_s;
  result.elapsed_s = args.samples.empty() ? 0.0 : args.samples.back().elapsed_s;
  result.iterations = args.iterations;
  result.started_at = args.started_at;
  result.ended_at = now_iso();
  result.metrics = args.metrics;
  result.errors = args.errors;
  result.sdk_version = args.sdk_version;
  result.description = args.description;
  result.commit_sha = git_commit_sha();
  result.run_error = run_error;
  result.samples = args.samples;

  try {
    write_reports(result, args.out_dir);
  } catch (const std::exception& e) {
    std::cout << "[finish_run] write_reports() failed, continuing: " << e.what() << "\n";
  } catch (...) {
    std::cout << "[finish_run] write_reports() failed, continuing (unknown exception)\n";
  }
  return result;
}

LiveReporter::LiveReporter(std::string test_name, double duration_s,
                           std::optional<double> interval_s)
    : test_name_(std::move(test_name)),
      duration_s_(duration_s),
      interval_s_(interval_s.value_or(live_interval_seconds())) {}

namespace {
double monotonic_now_s() {
  return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
}  // namespace

void LiveReporter::update(const std::vector<Sample>& samples, std::optional<int> errors,
                          const std::map<std::string, std::string>& extra, bool force) {
  if (endurance_verbose() || samples.empty()) {
    return;
  }
  const double now = monotonic_now_s();
  if (!force && printed_once_ && (now - last_print_s_) < interval_s_) {
    return;
  }
  last_print_s_ = now;
  printed_once_ = true;
  std::cout << format(samples, errors, extra) << "\n";
}

std::string LiveReporter::format(const std::vector<Sample>& samples, std::optional<int> errors,
                                 const std::map<std::string, std::string>& extra) const {
  const Sample& first = samples.front();
  const Sample& last = samples.back();
  const double elapsed = last.elapsed_s;
  const int pct =
      duration_s_ > 0.0 ? std::min(100, static_cast<int>(elapsed / duration_s_ * 100)) : 0;
  const double rss_start_mb = static_cast<double>(first.rss_bytes) / 1e6;
  const double rss_now_mb = static_cast<double>(last.rss_bytes) / 1e6;
  double avg_cpu = 0.0;
  for (const auto& s : samples) {
    avg_cpu += s.cpu_percent;
  }
  avg_cpu /= static_cast<double>(samples.size());

  std::ostringstream out;
  const std::string rule(46, '-');
  out << rule << "\n";
  out << "\U0001F680 Endurance Test — " << test_name_ << "\n";
  out << rule << "\n\n";
  out << "Duration       " << format_duration(duration_s_) << "\n";
  out << "Elapsed        " << format_duration(elapsed) << "\n";
  out << "Progress       " << pct << "%\n\n";
  out << "Iterations     " << (last.cycle + 1) << "\n";
  if (errors.has_value()) {
    out << "Errors         " << *errors << "\n";
  }
  out << "\nResources\n";
  {
    std::ostringstream delta;
    const double d = rss_now_mb - rss_start_mb;
    delta << (d >= 0 ? "+" : "") << std::fixed << std::setprecision(1) << d << " MB";
    out << "  RSS           " << std::fixed << std::setprecision(0) << rss_start_mb << " MB → "
        << rss_now_mb << " MB   (" << delta.str() << ")\n";
  }
  out << "  CPU           avg " << std::fixed << std::setprecision(1) << avg_cpu << "%\n";
  out << "  Threads       " << first.num_threads << " → " << last.num_threads << "\n";
  out << "  File desc.    " << first.num_fds << " → " << last.num_fds << "\n";
  for (const auto& [key, value] : extra) {
    std::ostringstream line;
    line << "  " << std::left << std::setw(13) << key << " " << value;
    out << line.str() << "\n";
  }
  const std::string status_line =
      (errors.has_value() && *errors > 0) ? "\U0001F7E1 Errors detected" : "\U0001F7E2 Healthy";
  out << "\nStatus         " << status_line << "\n";
  out << rule;
  return out.str();
}

void finish_and_check(const std::vector<Sample>& samples, LiveReporter& live,
                      FinishAndCheckArgs args) {
  if (!samples.empty()) {
    live.update(samples, args.errors, args.extra, /*force=*/true);
  }
  FinishRunArgs run_args;
  run_args.test_name = args.test_name;
  run_args.sdk = args.sdk;
  run_args.duration_s = args.duration_s;
  run_args.started_at = args.started_at;
  run_args.samples = samples;
  run_args.metrics = args.metrics;
  run_args.iterations = args.iterations;
  run_args.errors = args.errors;
  run_args.sdk_version = args.sdk_version;
  run_args.description = args.description;
  run_args.run_error = args.run_error;
  run_args.out_dir = args.out_dir;

  const RunResult result = finish_run(std::move(run_args));
  if (result.status == "FAIL") {
    std::string names;
    for (const auto& m : args.metrics) {
      if (m.status == "fail") {
        if (!names.empty()) {
          names += ", ";
        }
        names += m.name;
      }
    }
    throw std::runtime_error("endurance test detected a failure in: " + names +
                             " — see the report for details");
  }
}

}  // namespace endurance
