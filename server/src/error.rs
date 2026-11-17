use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

use crate::db::AccountSummary;

#[derive(Debug)]
pub enum AppError {
    BadRequest(&'static str, String),
    Unauthorized(&'static str, String),
    Forbidden(&'static str, String),
    NotFound(&'static str, String),
    TooManyRequests(String),
    Conflict {
        code: &'static str,
        message: String,
        account_summary: Option<AccountSummary>,
    },
    Internal(String),
}

#[derive(Serialize)]
struct ErrorPayload {
    error: &'static str,
    message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    account_summary: Option<AccountSummary>,
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        match self {
            AppError::BadRequest(code, message) => (
                StatusCode::BAD_REQUEST,
                Json(ErrorPayload {
                    error: code,
                    message,
                    account_summary: None,
                }),
            )
                .into_response(),
            AppError::Unauthorized(code, message) => (
                StatusCode::UNAUTHORIZED,
                Json(ErrorPayload {
                    error: code,
                    message,
                    account_summary: None,
                }),
            )
                .into_response(),
            AppError::Forbidden(code, message) => (
                StatusCode::FORBIDDEN,
                Json(ErrorPayload {
                    error: code,
                    message,
                    account_summary: None,
                }),
            )
                .into_response(),
            AppError::NotFound(code, message) => (
                StatusCode::NOT_FOUND,
                Json(ErrorPayload {
                    error: code,
                    message,
                    account_summary: None,
                }),
            )
                .into_response(),
            AppError::TooManyRequests(message) => (
                StatusCode::TOO_MANY_REQUESTS,
                Json(ErrorPayload {
                    error: "rate_limited",
                    message,
                    account_summary: None,
                }),
            )
                .into_response(),
            AppError::Conflict {
                code,
                message,
                account_summary,
            } => (
                StatusCode::CONFLICT,
                Json(ErrorPayload {
                    error: code,
                    message,
                    account_summary,
                }),
            )
                .into_response(),
            AppError::Internal(err_msg) => {
                tracing::error!("internal server error: {}", err_msg);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorPayload {
                        error: "internal_error",
                        message: "云端服务内部错误，请稍后重试".to_string(),
                        account_summary: None,
                    }),
                )
                    .into_response()
            }
        }
    }
}
