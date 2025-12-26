"""
Smart Irrigation - Sistem Cerdas API (FastAPI)

Endpoints:
- GET  /health
- GET  /api/metadata
- POST /api/predict
- POST /api/predict-batch
- POST /api/pump/control
- GET  /api/pump/last

ENV:
- MODEL_PATH=FiksModel_RandomForest_Evabenir.joblib
- FEATURE_ORDER=Humidity,Rainfall,Sunlight,Soil_Moisture

Pump control forward (opsional):
- PUMP_CONTROL_MODE=http | mqtt | both   (default: http)
- ESP32_CONTROL_URL=http://192.168.4.1/pump
- ESP32_TIMEOUT=3

MQTT (opsional):
- MQTT_HOST=...
- MQTT_PORT=1883
- MQTT_TOPIC=smart_irrigation/pump/control
- MQTT_USERNAME=...
- MQTT_PASSWORD=...

Security (opsional):
- CONTROL_API_KEY=rahasia
  client kirim header: X-API-KEY: rahasia
"""

from __future__ import annotations

import os
import json
import time
from typing import List, Optional, Dict, Any

import joblib
import pandas as pd
import requests
from fastapi import FastAPI, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, conint, confloat

try:
    import paho.mqtt.client as mqtt  # optional
except Exception:
    mqtt = None


# =========================
# Config
# =========================
MODEL_PATH = os.getenv("MODEL_PATH", "FiksModel_RandomForest_Evabenir.joblib")
DEFAULT_FEATURE_ORDER = os.getenv(
    "FEATURE_ORDER", "Humidity,Rainfall,Sunlight,Soil_Moisture"
).split(",")

PUMP_CONTROL_MODE = os.getenv("PUMP_CONTROL_MODE", "http").lower()  # http | mqtt | both
ESP32_CONTROL_URL = os.getenv("ESP32_CONTROL_URL", "http://192.168.4.1/pump")
ESP32_TIMEOUT = float(os.getenv("ESP32_TIMEOUT", "3"))

MQTT_HOST = os.getenv("MQTT_HOST", "")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
MQTT_TOPIC = os.getenv("MQTT_TOPIC", "smart_irrigation/pump/control")
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")

CONTROL_API_KEY = os.getenv("CONTROL_API_KEY", "")

# =========================
# App
# =========================
app = FastAPI(
    title="Smart Irrigation - Sistem Cerdas API",
    version="1.2.0",
    description="REST API untuk prediksi status pompa (RandomForest) + kontrol pompa (HTTP/MQTT).",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # batasi origin saat production
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

_model: Optional[Any] = None
_feature_order: Optional[List[str]] = None
_model_load_error: Optional[str] = None

_last_pump_command: Dict[str, Any] = {
    "ts": None,
    "pump_status": None,
    "source": None,
    "reason": None,
    "mode": None,
    "result": None,
}


# =========================
# Helpers
# =========================
def _check_api_key(x_api_key: Optional[str]) -> None:
    if CONTROL_API_KEY:
        if not x_api_key or x_api_key != CONTROL_API_KEY:
            raise HTTPException(status_code=401, detail="Unauthorized: invalid X-API-KEY")


def _load_model() -> None:
    """Mendukung 2 format:
    1) dict bundle: {'model': <Pipeline>, 'feature_order': [...]}
    2) langsung sklearn Pipeline/Estimator (feature_order pakai FEATURE_ORDER env/default)
    """
    global _model, _feature_order

    obj = joblib.load(MODEL_PATH)

    if isinstance(obj, dict) and "model" in obj:
        _model = obj["model"]
        fo = obj.get("feature_order") or DEFAULT_FEATURE_ORDER
        _feature_order = [c.strip() for c in fo]
    else:
        _model = obj
        _feature_order = [c.strip() for c in DEFAULT_FEATURE_ORDER]


def _require_model_ready() -> None:
    if _model is None or _feature_order is None:
        raise HTTPException(
            status_code=503,
            detail=f"Model belum siap. Error load: {_model_load_error}",
        )


def _predict_df(df: pd.DataFrame) -> List[Dict[str, Any]]:
    _require_model_ready()

    X = df[_feature_order].copy()
    X = X.apply(pd.to_numeric, errors="coerce")

    pred = _model.predict(X).astype(int).tolist()

    prob_on = None
    if hasattr(_model, "predict_proba"):
        try:
            proba = _model.predict_proba(X)
            prob_on = [float(p[1]) for p in proba]
        except Exception:
            prob_on = None

    out: List[Dict[str, Any]] = []
    for i, yhat in enumerate(pred):
        label = "ON" if yhat == 1 else "OFF"
        used = {k: float(df.iloc[i][k]) for k in _feature_order}
        out.append(
            {
                "pump_status": int(yhat),
                "pump_label": label,
                "probability_on": None if prob_on is None else prob_on[i],
                "used_features": used,
            }
        )
    return out


def _send_pump_http(payload: Dict[str, Any]) -> Dict[str, Any]:
    try:
        r = requests.post(ESP32_CONTROL_URL, json=payload, timeout=ESP32_TIMEOUT)
        return {
            "ok": r.ok,
            "status_code": r.status_code,
            "response_text": r.text[:500],
            "url": ESP32_CONTROL_URL,
        }
    except Exception as e:
        return {
            "ok": False,
            "status_code": None,
            "error": str(e),
            "url": ESP32_CONTROL_URL,
        }


def _send_pump_mqtt(payload: Dict[str, Any]) -> Dict[str, Any]:
    if mqtt is None:
        return {"ok": False, "error": "paho-mqtt belum terpasang / gagal import."}
    if not MQTT_HOST:
        return {"ok": False, "error": "MQTT_HOST belum di-set."}

    client_id = f"api-{int(time.time())}"
    client = mqtt.Client(client_id=client_id, clean_session=True)

    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    try:
        client.connect(MQTT_HOST, MQTT_PORT, keepalive=10)
        msg = json.dumps(payload)
        info = client.publish(MQTT_TOPIC, msg, qos=1, retain=False)
        info.wait_for_publish()
        client.disconnect()
        return {
            "ok": True,
            "host": MQTT_HOST,
            "port": MQTT_PORT,
            "topic": MQTT_TOPIC,
            "payload": payload,
        }
    except Exception as e:
        return {"ok": False, "error": str(e), "host": MQTT_HOST, "port": MQTT_PORT, "topic": MQTT_TOPIC}


# =========================
# Startup (SAFE)
# =========================
@app.on_event("startup")
def _startup() -> None:
    global _model_load_error
    try:
        _load_model()
        _model_load_error = None
    except Exception as e:
        # Jangan crash server: tetap hidup supaya /docs bisa dibuka
        _model_load_error = str(e)


# =========================
# Schemas
# =========================
class PredictRequest(BaseModel):
    Humidity: confloat(ge=0, le=100) = Field(..., description="Kelembapan udara (%)")
    Rainfall: conint(ge=0, le=1) = Field(..., description="Hujan (0=tidak, 1=ya)")
    Sunlight: confloat(ge=0) = Field(..., description="Intensitas cahaya (nilai sensor)")
    Soil_Moisture: confloat(ge=0, le=100) = Field(..., description="Kelembapan tanah (%)")


class PredictResponse(BaseModel):
    pump_status: int
    pump_label: str
    probability_on: Optional[float] = None
    used_features: Dict[str, float]


class PumpControlRequest(BaseModel):
    pump_status: conint(ge=0, le=1) = Field(..., description="0=OFF, 1=ON")
    source: Optional[str] = Field("api", description="Sumber perintah (api/flutter/esp32)")
    reason: Optional[str] = Field(None, description="Alasan perintah (manual/auto/schedule)")
    correlation_id: Optional[str] = Field(None, description="ID untuk tracing request")


class PumpControlResponse(BaseModel):
    accepted: bool
    mode: str
    forwarded_results: List[Dict[str, Any]]
    command: Dict[str, Any]


# =========================
# Routes
# =========================
@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "status": "ok",
        "model_path": MODEL_PATH,
        "model_loaded": _model is not None,
        "model_load_error": _model_load_error,
        "feature_order": _feature_order,
        "pump_control": {
            "mode": PUMP_CONTROL_MODE,
            "http_url": ESP32_CONTROL_URL if PUMP_CONTROL_MODE in ("http", "both") else None,
            "mqtt_host": MQTT_HOST if PUMP_CONTROL_MODE in ("mqtt", "both") else None,
            "mqtt_topic": MQTT_TOPIC if PUMP_CONTROL_MODE in ("mqtt", "both") else None,
            "api_key_required": bool(CONTROL_API_KEY),
        },
    }


@app.get("/api/metadata")
def metadata() -> Dict[str, Any]:
    _require_model_ready()
    return {"feature_order": _feature_order, "label_mapping": {"0": "OFF", "1": "ON"}}


@app.post("/api/predict", response_model=PredictResponse)
def predict(req: PredictRequest) -> PredictResponse:
    df = pd.DataFrame([req.model_dump()])
    res = _predict_df(df)[0]
    return PredictResponse(**res)


@app.post("/api/predict-batch", response_model=List[PredictResponse])
def predict_batch(reqs: List[PredictRequest]) -> List[PredictResponse]:
    if not reqs:
        raise HTTPException(status_code=400, detail="Request list kosong.")
    df = pd.DataFrame([r.model_dump() for r in reqs])
    res = _predict_df(df)
    return [PredictResponse(**x) for x in res]


@app.get("/api/pump/last")
def pump_last() -> Dict[str, Any]:
    return _last_pump_command


@app.post("/api/pump/control", response_model=PumpControlResponse)
def pump_control(
    req: PumpControlRequest,
    x_api_key: Optional[str] = Header(default=None, alias="X-API-KEY"),
) -> PumpControlResponse:
    _check_api_key(x_api_key)

    mode = PUMP_CONTROL_MODE
    if mode not in ("http", "mqtt", "both"):
        raise HTTPException(status_code=500, detail="PUMP_CONTROL_MODE harus: http | mqtt | both")

    payload = {
        "pump_status": int(req.pump_status),
        "pump_label": "ON" if req.pump_status == 1 else "OFF",
        "source": req.source,
        "reason": req.reason,
        "correlation_id": req.correlation_id,
        "ts": int(time.time()),
    }

    results: List[Dict[str, Any]] = []
    if mode in ("http", "both"):
        results.append({"via": "http", **_send_pump_http(payload)})
    if mode in ("mqtt", "both"):
        results.append({"via": "mqtt", **_send_pump_mqtt(payload)})

    accepted = any(r.get("ok") is True for r in results) if results else False

    _last_pump_command.update(
        {
            "ts": payload["ts"],
            "pump_status": payload["pump_status"],
            "source": payload["source"],
            "reason": payload["reason"],
            "mode": mode,
            "result": results,
        }
    )

    return PumpControlResponse(
        accepted=accepted,
        mode=mode,
        forwarded_results=results,
        command=payload,
    )
