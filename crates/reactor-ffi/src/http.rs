use async_trait::async_trait;
use reactor_core::error::CoreError;
use reactor_core::http::{HttpClient, HttpRequest, HttpResponse, Method};

pub struct ReqwestHttpClient {
    inner: reqwest::Client,
}

impl ReqwestHttpClient {
    /// `local` disables TLS certificate verification so self-signed / IP /
    /// localhost dev servers work over HTTPS.
    pub fn new(local: bool) -> Self {
        let inner = if local {
            reqwest::Client::builder()
                .danger_accept_invalid_certs(true)
                .build()
                .unwrap_or_else(|_| reqwest::Client::new())
        } else {
            reqwest::Client::new()
        };
        Self { inner }
    }
}

#[async_trait]
impl HttpClient for ReqwestHttpClient {
    async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
        let mut builder = match req.method {
            Method::Get => self.inner.get(&req.url),
            Method::Post => self.inner.post(&req.url),
            Method::Put => self.inner.put(&req.url),
            Method::Delete => self.inner.delete(&req.url),
        };
        for (k, v) in &req.headers {
            builder = builder.header(k, v);
        }
        if let Some(body) = req.body {
            builder = builder.body(body);
        }
        let resp = builder
            .send()
            .await
            .map_err(|e| CoreError::Http(e.to_string()))?;
        let status = resp.status().as_u16();
        let headers = resp
            .headers()
            .iter()
            .filter_map(|(k, v)| v.to_str().ok().map(|s| (k.to_string(), s.to_string())))
            .collect();
        let body = resp
            .bytes()
            .await
            .map_err(|e| CoreError::Http(e.to_string()))?
            .to_vec();
        Ok(HttpResponse {
            status,
            headers,
            body,
        })
    }
}
