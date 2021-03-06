(ns uk.ac.cam.db538.cryptosms.storage.EncryptedStorage
  (:require [uk.ac.cam.db538.cryptosms.utils :as utils]
            [uk.ac.cam.db538.cryptosms.byte-arrays :as byte-arrays]
            [uk.ac.cam.db538.cryptosms.crypto.random :as random]
            [uk.ac.cam.db538.cryptosms.storage.binary-file :as binary-file] )
  (:import (java.io File RandomAccessFile)
           (uk.ac.cam.db538.cryptosms CryptoKey)
           (uk.ac.cam.db538.cryptosms IErrorCallback)
           (uk.ac.cam.db538.cryptosms.storage.binary_file BinaryFile) )
  (:gen-class
    :implements [uk.ac.cam.db538.cryptosms.storage.IStorage]
    :state state
    :init init
    :constructors 
      { [String String uk.ac.cam.db538.cryptosms.CryptoKey uk.ac.cam.db538.cryptosms.IErrorCallback] [] } ))

(defrecord EncryptedStorageState
  [ #^clojure.core.Vec     crypto-key
    #^clojure.lang.Agent   file-agent ])

(defn -init
  "Initializes the EncryptedStorage class.
   Arguments:
     - path to the encrypted file
     - path to the journal
     - instance of CryptoKey to encrypt the file with
     - callback class in case of exception"
  [ #^String file-storage #^String file-journal #^CryptoKey crypto-key #^IErrorCallback callback ]
  [ []
    (EncryptedStorageState.
      (byte-arrays/to-vector (. crypto-key getKey))
      (binary-file/open file-storage file-journal #(. callback onError %1) )) ])

(defn -getConversationThread
  [ recipient ]
  nil)

