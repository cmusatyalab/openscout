FROM bamos/openface
LABEL Satyalab, satya-group@lists.andrew.cmu.edu

ARG DEBIAN_FRONTEND=noninteractive

RUN pip install --upgrade pip
RUN python -m pip install --no-cache-dir \
    flask-restful

COPY ./openface-rest.py /root/openface-rest.py

EXPOSE 5000
ENTRYPOINT  ["python", "/root/openface-rest.py", "/openscout/server/training/"]
