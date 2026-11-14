FROM docker.io/library/python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
ENV PYTHONUNBUFFERED=1 HF_HOME=/models
EXPOSE 8090
CMD ["python", "server.py"]
