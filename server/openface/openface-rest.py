#!/usr/bin/env python2

# Copyright 2020 Carnegie Mellon University
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import subprocess
import time
from flask import Flask, jsonify, request
from flask_restful import Resource, Api
start = time.time()

import argparse
import cv2
import os
import pickle
import sys
import logging

from operator import itemgetter

import numpy as np
np.set_printoptions(precision=2)
import pandas as pd

import openface

from sklearn.pipeline import Pipeline
from sklearn.discriminant_analysis import LinearDiscriminantAnalysis as LDA
from sklearn.preprocessing import LabelEncoder
from sklearn.svm import SVC
from sklearn.grid_search import GridSearchCV
from sklearn.mixture import GMM
from sklearn.tree import DecisionTreeClassifier
from sklearn.naive_bayes import GaussianNB

fileDir = os.path.dirname(os.path.realpath(__file__))
modelDir = os.path.join(fileDir, 'openface/models')
dlibModelDir = os.path.join(modelDir, 'dlib')
openfaceModelDir = os.path.join(modelDir, 'openface')
dlibFacePredictor = os.path.join(dlibModelDir,
            "shape_predictor_68_face_landmarks.dat")
networkModel = os.path.join(openfaceModelDir,
            'nn4.small2.v1.t7')

imgDim = 96
verbose = True
ldaDim = -1
'''
        choices=[
            'LinearSvm',
            'GridSearchSvm',
            'GMM',
            'RadialSvm',
            'DecisionTree',
            'GaussianNB',
            'DBN'],
'''
classifier = 'LinearSvm'
logging.basicConfig()
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

def getRep(img):
    start = time.time()

    nparr = np.fromstring(img, np.uint8)
    # decode image
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    rgbImg = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)

    start = time.time()

    bbs = align.getAllFaceBoundingBoxes(rgbImg)

    if len(bbs) == 0:
        raise Exception("Unable to find a face.")
    if verbose:
        logger.debug("Face detection took {} seconds.".format(time.time() - start))

    reps = []
    for bb in bbs:
        start = time.time()
        alignedFace = align.align(
            imgDim,
            rgbImg,
            bb,
            landmarkIndices=openface.AlignDlib.OUTER_EYES_AND_NOSE)
        if alignedFace is None:
            raise Exception("Unable to align image.")
        if verbose:
            logger.debug("Alignment took {} seconds.".format(time.time() - start))
            logger.debug("This bbox is centered at {}, {}".format(bb.center().x, bb.center().y))

        start = time.time()
        rep = net.forward(alignedFace)
        if verbose:
            logger.debug("Neural network forward pass took {} seconds.".format(
                time.time() - start))
        reps.append((bb, rep))
    sreps = sorted(reps, key=lambda x: x[0])
    return sreps


def train():
    alignDir = "/tmp/aligned/"
    featureDir = "/tmp/features/"
    #align
    alignCmd ="/root/openface/util/align-dlib.py " + workDir + " align outerEyesAndNose " + alignDir + " --size 96"
    logger.info("Aligning training images...")
    subprocess.call(alignCmd, shell=True)
    #delete cache file
    deleteCmd = "rm -rf /tmp/aligned/cache.t7"
    logger.info("Removing cache file...")
    subprocess.call(deleteCmd, shell=True)
    #represent
    representCmd = "/root/openface/batch-represent/main.lua -outDir " + featureDir + " -data " + alignDir
    logger.info("Generating representations...")
    subprocess.call(representCmd, shell=True)
    #generate SVM
    logger.info("Loading embeddings...")
    fname = "{}/labels.csv".format(featureDir)
    labels = pd.read_csv(fname, header=None).as_matrix()[:, 1]
    labels = map(itemgetter(1),
                map(os.path.split,
                    map(os.path.dirname, labels)))  # Get the directory.
    fname = "{}/reps.csv".format(featureDir)
    embeddings = pd.read_csv(fname, header=None).as_matrix()
    le = LabelEncoder().fit(labels)
    labelsNum = le.transform(labels)
    nClasses = len(le.classes_)
    logger.info("Training for {} classes.".format(nClasses))

    if classifier == 'LinearSvm':
        clf = SVC(C=1, kernel='linear', probability=True)
    elif classifier == 'GridSearchSvm':
        logger.info("""
        Warning: In our experiences, using a grid search over SVM hyper-parameters only
        gives marginally better performance than a linear SVM with C=1 and
        is not worth the extra computations of performing a grid search.
        """)
        param_grid = [
            {'C': [1, 10, 100, 1000],
            'kernel': ['linear']},
            {'C': [1, 10, 100, 1000],
            'gamma': [0.001, 0.0001],
            'kernel': ['rbf']}
        ]
        clf = GridSearchCV(SVC(C=1, probability=True), param_grid, cv=5)
    elif classifier == 'GMM':  # Doesn't work best
        clf = GMM(n_components=nClasses)

    # ref:
    # http://scikit-learn.org/stable/auto_examples/classification/plot_classifier_comparison.html#example-classification-plot-classifier-comparison-py
    elif classifier == 'RadialSvm':  # Radial Basis Function kernel
        # works better with C = 1 and gamma = 2
        clf = SVC(C=1, kernel='rbf', probability=True, gamma=2)
    elif classifier == 'DecisionTree':  # Doesn't work best
        clf = DecisionTreeClassifier(max_depth=20)
    elif classifier == 'GaussianNB':
        clf = GaussianNB()

    # ref: https://jessesw.com/Deep-Learning/
    elif classifier == 'DBN':
        from nolearn.dbn import DBN
        clf = DBN([embeddings.shape[1], 500, labelsNum[-1:][0] + 1],  # i/p nodes, hidden nodes, o/p nodes
                learn_rates=0.3,
                # Smaller steps mean a possibly more accurate result, but the
                # training will take longer
                learn_rate_decays=0.9,
                # a factor the initial learning rate will be multiplied by
                # after each iteration of the training
                epochs=300,  # no of iternation
                # dropouts = 0.25, # Express the percentage of nodes that
                # will be randomly dropped as a decimal.
                verbose=1)

    if ldaDim > 0:
        clf_final = clf
        clf = Pipeline([('lda', LDA(n_components=ldaDim)),
                        ('clf', clf_final)])

    clf.fit(embeddings, labelsNum)

    fName = "{}/classifier.pkl".format(workDir)
    logger.info("Saving classifier to '{}'".format(fName))
    with open(fName, 'w') as f:
        pickle.dump((le, clf), f)


def infer(img):
    fName = "{}/classifier.pkl".format(workDir)
    with open(fName, 'rb') as f:
        if sys.version_info[0] < 3:
                (le, clf) = pickle.load(f)
        else:
                (le, clf) = pickle.load(f, encoding='latin1')

    reps = getRep(img)
    if len(reps) > 1:
        logger.info("List of faces in image from left to right")

    persons = []
    for r in reps:
        rep = r[1].reshape(1, -1)
        bbx = r[0]
        start = time.time()
        predictions = clf.predict_proba(rep).ravel()
        maxI = np.argmax(predictions)
        person = le.inverse_transform(maxI)
        confidence = predictions[maxI]
        if verbose:
            logger.debug("Prediction took {} seconds.".format(time.time() - start))
        if isinstance(clf, GMM):
            dist = np.linalg.norm(rep - clf.means_[maxI])
            logger.info("  + Distance from the mean: {}".format(dist))
        person = {
            'name': person.decode('utf-8'),
            'confidence': confidence,
            'bb-tl-x': bbx.left(),
            'bb-tl-y': bbx.top(),
            'bb-br-x': bbx.right(),
            'bb-br-y': bbx.bottom(),
        }
        persons.append(person)
    for p in persons:
        logger.info("Predict {} with {:.2f} confidence.".format(p['name'], p['confidence']))
    return persons

app = Flask(__name__)
api = Api(app)

@api.resource('/train')
class Training(Resource):
    def get(self):
        train()
        return {'training': 'successful'}
        
@api.resource('/infer')
class Infer(Resource):
    def post(self):
        persons = {}
        try:
            persons = infer(request.data)
        except Exception as e:
            logger.debug(e)
        return jsonify(persons)

if __name__ == '__main__':

    parser = argparse.ArgumentParser()

    parser.add_argument('--port', type=int, help="Port to listen on (default=5000).", default=5000)

    parser.add_argument(
        'workDir',
        type=str,
        help="The input work directory containing 'reps.csv' and 'labels.csv'. Obtained from aligning a directory with 'align-dlib' and getting the representations with 'batch-represent'.")

    args = parser.parse_args()

    start = time.time()

    align = openface.AlignDlib(dlibFacePredictor)
    net = openface.TorchNeuralNet(networkModel, imgDim=imgDim,
                                  cuda=False)
    workDir = args.workDir
    if verbose:
        logger.debug("Loading the dlib and OpenFace models took {} seconds.".format(
            time.time() - start))
        start = time.time()

    app.run(host='0.0.0.0', port=args.port)
