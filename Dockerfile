FROM nvidia/cuda:10.0-cudnn7-devel-ubuntu18.04
LABEL Satyalab, satya-group@lists.andrew.cmu.edu

ARG DEBIAN_FRONTEND=noninteractive

# Install build and runtime dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    clinfo \
    curl \
    libgtk-3-0 \
    libsm6 \
    libxext6 \
    libxrender1 \
    python3 \
    python3-pip \
    python3-pyqt5 \
    wget \
 && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

#upgrade pip, otherwise tensorflow 1.15.0 will not be found
RUN pip3 install --upgrade pip

# Install Tensorflow and Gabriel's external dependencies
RUN python3 -m pip install --no-cache-dir \
    'opencv-python<5' \
    protobuf \
    py-cpuinfo \
    'PyQt5==5.14.0' \
    pyzmq \
    'setuptools==41.0.0' \
    'websockets==8.0.0' \
    zmq 
#Install Filebeats to push log data to ELK
RUN wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | apt-key add -
RUN echo "deb https://artifacts.elastic.co/packages/oss-7.x/apt stable main" | tee -a /etc/apt/sources.list.d/elastic-7.x.list
RUN apt-get update && apt-get install -y \
    filebeat \
 && apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# You can speed up build slightly by reducing build context with
#     git archive --format=tgz HEAD | docker build -t openscout -
COPY . openscout
WORKDIR openscout/server
#download and extract models
RUN mkdir model
RUN wget http://download.tensorflow.org/models/object_detection/ssd_resnet50_v1_fpn_shared_box_predictor_640x640_coco14_sync_2018_07_03.tar.gz -O ./model/coco.tar.gz  \
    && tar -C model -xzvf ./model/coco.tar.gz \
    && mv ./model/ssd_resnet50_v1_fpn_shared_box_predictor_640x640_coco14_sync_2018_07_03 ./model/coco
RUN wget http://download.tensorflow.org/models/object_detection/ssd_mobilenet_v2_oid_v4_2018_12_12.tar.gz -O ./model/oidv4.tar.gz \
    && tar -C model -xzvf ./model/oidv4.tar.gz \
    && mv ./model/ssd_mobilenet_v2_oid_v4_2018_12_12 ./model/oidv4
RUN wget https://raw.githubusercontent.com/tensorflow/models/master/research/object_detection/data/mscoco_label_map.pbtxt -O ./model/coco/label_map.pbtxt
RUN wget https://raw.githubusercontent.com/tensorflow/models/master/research/object_detection/data/oid_v4_label_map.pbtxt -O ./model/oidv4/label_map.pbtxt

RUN python3 -m pip install -r requirements.txt
COPY ./server/elk/filebeat.yml /etc/filebeat/filebeat.yml
RUN chmod go-w /etc/filebeat/filebeat.yml

EXPOSE 5555 9099
ENTRYPOINT ["./entrypoint.sh"]
