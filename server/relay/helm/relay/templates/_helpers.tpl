{{- define "relay.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "relay.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "relay.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "relay.labels" -}}
app.kubernetes.io/name: {{ include "relay.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "relay.envSecretName" -}}
{{- if .Values.secrets.existingSecret -}}
{{- .Values.secrets.existingSecret -}}
{{- else -}}
{{- printf "%s-env" (include "relay.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "relay.postgres.fullname" -}}
{{- printf "%s-postgres" (include "relay.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "relay.redis.fullname" -}}
{{- printf "%s-redis" (include "relay.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "relay.postgres.secretName" -}}
{{- if .Values.postgres.auth.existingSecret -}}
{{- .Values.postgres.auth.existingSecret -}}
{{- else -}}
{{- printf "%s-postgres" (include "relay.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "relay.databaseUrl" -}}
{{- if .Values.secrets.databaseUrl -}}
{{- .Values.secrets.databaseUrl -}}
{{- else if .Values.postgres.enabled -}}
{{- printf "postgres://%s:%s@%s:%v/%s" .Values.postgres.auth.username .Values.postgres.auth.password (include "relay.postgres.fullname" .) .Values.postgres.service.port .Values.postgres.auth.database -}}
{{- else -}}
{{- "" -}}
{{- end -}}
{{- end -}}

{{- define "relay.redisUrl" -}}
{{- if .Values.secrets.redisUrl -}}
{{- .Values.secrets.redisUrl -}}
{{- else if .Values.redis.enabled -}}
{{- printf "redis://%s:%v" (include "relay.redis.fullname" .) .Values.redis.service.port -}}
{{- else -}}
{{- "" -}}
{{- end -}}
{{- end -}}
