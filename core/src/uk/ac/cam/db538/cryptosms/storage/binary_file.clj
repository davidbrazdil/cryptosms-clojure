(ns uk.ac.cam.db538.cryptosms.storage.binary-file
  (:use [clojure.test :only (with-test, is, deftest) ])
  (:require [uk.ac.cam.db538.cryptosms.utils :as utils]
            [uk.ac.cam.db538.cryptosms.byte-arrays :as byte-arrays]
            [uk.ac.cam.db538.cryptosms.crypto.random :as random]
            [uk.ac.cam.db538.cryptosms.serializables.align :as align]
            [uk.ac.cam.db538.cryptosms.serializables.composite :as composite]
            [uk.ac.cam.db538.cryptosms.serializables.uint :as uint]
            [uk.ac.cam.db538.cryptosms.serializables.raw-data :as raw-data] )
  (:import (java.io File RandomAccessFile FileNotFoundException)
           (uk.ac.cam.db538.cryptosms.storage StorageFileException) ))

(defrecord BinaryFile 
  [ #^String             name-file
    #^String             name-journal
    #^RandomAccessFile   pointer-file
    #^RandomAccessFile   pointer-journal
                         entry-count
                         error-callback
                         cache-atom
  ])

(def journal-entry-length 256)
(def binaryfile-entry-length (- journal-entry-length 9) )
(def binaryfile-entry-length-aligned journal-entry-length)
(def binaryfile-entry-structure
  (align/create binaryfile-entry-length-aligned
    (raw-data/create :data binaryfile-entry-length))) 
(def journal-entry-type-entry 0)
(def journal-entry-type-commit 1)
(def journal-entry-structure
  (align/create journal-entry-length
    (composite/create 
      [ (uint/uint8      :type)
        (uint/uint64     :index)
        (raw-data/create :data binaryfile-entry-length) ])))
(def journal-dummy-data (random/next-vector  binaryfile-entry-length))

(defn- file-safe-close
  "Closes opened file."
  [ #^RandomAccessFile pointer ]
  (. pointer close))
  
(with-test
  (defn- #^RandomAccessFile file-safe-open
    "If the directory for file doesn't exist, attempts to create it first, and 
     then opens the file and returns the RandomAccessFile object."
    [ #^String filename ]
    (let [ directory (. (new File filename) getParent) ]
      ; create the file's directory (if necessary)
      (if (and (not (= directory nil)) (> (count directory) 0))
        (. (new File directory) mkdirs))
      ; open the file and return the object
      (new RandomAccessFile filename "rw")))
  (let [ tmpdir         (System/getProperty "java.io.tmpdir")
         dir            (str tmpdir "/cryptosms" (rand-int 1000000) "/binary/file/test")
         str-file       (str dir "/binaryfile.dat")
         pointer-file   (file-safe-open str-file)] 
    ; file exists
    (is (. (new File str-file) exists))
    ; length is zero
    (is (= (. (new File str-file) length) 0))
    ; write something into it
    (. pointer-file writeInt 1000) 
    ; close it
    (file-safe-close pointer-file)  
    ; length is four
    (is (= (. (new File str-file) length) 4))
    ; multiple opening should be fine
    (file-safe-open str-file)
    (file-safe-open str-file)
    (file-safe-open str-file)
    (file-safe-open str-file)
    ; fails if can't create/open the file
    (is (thrown? FileNotFoundException (file-safe-open "/file.dat")))
    ; clean up after yourself
    (. (new File str-file) delete) ))
  
(with-test
  (defn- #^RandomAccessFile file-safe-create-empty
    "If the given file already exists, it deletes it first and the calls 
     safe-open to create it."
    [ #^String filename ]
    (let [ file (new File filename) ]
      ; create the file's directory (if necessary)
      (if (. file exists)
        (. file delete))
      ; open the file and return the object
      (file-safe-open filename)))
  (let [ tmpdir         (System/getProperty "java.io.tmpdir")
         dir            (str tmpdir "/cryptosms" (rand-int 1000000) "/binary/file/test")
         str-file       (str dir "/binaryfile.dat")
         pointer-file   (file-safe-create-empty str-file)] 
    ; file exists
    (is (. (new File str-file) exists))
    ; length is zero
    (is (= (. (new File str-file) length) 0))
    ; write something into it
    (. pointer-file writeInt 1000) 
    ; close it
    (file-safe-close pointer-file)  
    ; length is four
    (is (= (. (new File str-file) length) 4))
    ; open it again
    (file-safe-create-empty str-file)
    ; file exists
    (is (. (new File str-file) exists))
    ; length is zero
    (is (= (. (new File str-file) length) 0)) ))

(defn- check-entry-index
  "Checks that the entry index is indeed inside the binary file
   or that it's the first free index, thus appending to the file."
  [ binary-file entry-index ]
  (if (> entry-index (:entry-count binary-file))
    (throw (new IndexOutOfBoundsException))))

(defn- read-entry-from-journal
  "Reads a general entry from the journal.
   Arguments:
     - binary file structure
     - index of entry in the journal (not where it should be written in the binary file)"
  [ binary-file journal-index ]
  (do
    ; move the file pointer to the right place
    (. (:pointer-journal binary-file) seek (* journal-index journal-entry-length))
    (let [ buffer (byte-arrays/create journal-entry-length) ]
      ; read the data
      (. (:pointer-journal binary-file) read buffer)
      ; parse and return
      ((:import journal-entry-structure)
        (byte-arrays/to-vector buffer)
        {}))))
  
(defn- write-entry-to-journal
  "Writes a general entry into the journal. 
   Arguments:
     - binary file structure
     - entry type (uint8 constants)
     - data vector
   Returns nil"
  [ binary-file entry-type entry-index entry-data ]
  (. (:pointer-journal binary-file) write
    (byte-arrays/from-vector
      ((:export journal-entry-structure) 
        {:type entry-type, :index entry-index, :data entry-data})) ))
  
(defn change-entry
  "Changes an entry in the binary file, which includes adding it into cache 
   of the agent and writing it into the journal. Returns altered binary file
   Arguments:
     - binary file structure
     - entry index in the file
     - data (object itself, not byte vector)
     - serializer (instance of a serializable that will be used to generate byte vector)
   Returns altered binary file"
  [ binary-file entry-index data serializer ]
  (do
    ; check the entry-index (it will throw exception if it's incorrect)
    (check-entry-index binary-file entry-index)
    ; if the entry index is the next free (appending to file), increase the entry count
    (let [ binary-file (assoc binary-file :entry-count
                         (if (= entry-index (:entry-count binary-file))
                           (inc (:entry-count binary-file))
                           (:entry-count binary-file))) ]
      ; write it to journal
      (write-entry-to-journal 
        binary-file 
        journal-entry-type-entry 
        entry-index 
        ((:export serializer) data))
      ; add to cache
      (swap! (:cache-atom binary-file) 
             #(assoc % entry-index data))
      ; return the altered binary-file
      binary-file)))
    
(defn get-entry
  "Returns an entry in the binary file. If it is in the cache, it will return that immediately,
   otherwise it will read it from the binary file.
   Arguments:
     - binary file structure
     - entry index in the file
     - serializer (will be used to recreate the object from bytes)
     - data necessary for the serializer"
  [ binary-file entry-index serializer import-data ]
  (do
    ; check the entry-index (it will throw exception if it's incorrect)
    (check-entry-index binary-file entry-index)
    ; look it up in the cache
    (let [ cache-entry (get @(:cache-atom binary-file) entry-index) ]
      (if (nil? cache-entry)
        ; if it's nil, we need to load it from the file (can't be in the journal)
        (do
          ; seek to the right spot
          (. (:pointer-file binary-file) seek (* entry-index binaryfile-entry-length-aligned))
          ; read the data
          (let [ buffer (byte-arrays/create binaryfile-entry-length-aligned) ] 
            (. (:pointer-file binary-file) read buffer)
            ; use the serializer to convert it into an object
            (let [ entry-aligned ((:import binaryfile-entry-structure) (byte-arrays/to-vector buffer) {})
                   entry         ((:import serializer) (:data entry-aligned) import-data) ]
              ; put it into the cache
              (swap! (:cache-atom binary-file) 
                     #(assoc % entry-index entry))
              ; return
              entry)))
        ; if it's in the cache, return it straight away
        cache-entry))))

(defn- save-changes
  "Checks that the operation in journal has committed and if so, it saves all the 
   changes into the binary file."
  [ binary-file ]
  (let [ length (/ (. (:pointer-journal binary-file) length) journal-entry-length) ]
    ; check it's not empty
    (if (> length 0)
      ; read the last entry and check it's committed
      (if (= (:type (read-entry-from-journal binary-file (- length 1))) 
             journal-entry-type-commit)
        ; loop through all the entries
        (loop [ journal-index 0 ]
          (if (< journal-index (- length 1))
            (let [ entry (read-entry-from-journal binary-file journal-index) ]
              ; check its type is ENTRY
              (if (= (:type entry) journal-entry-type-entry)
                (do
                  ; check the entry index
                  (check-entry-index binary-file (:index entry))
                  ; seek to the right spot
                  (. (:pointer-file binary-file) seek (* (:index entry) binaryfile-entry-length-aligned))
                  ; write the data
                  (. (:pointer-file binary-file) write
                    (byte-arrays/from-vector
                      ((:export binaryfile-entry-structure) entry)))
                  ; recur
                  (recur (inc journal-index)))
                ; throw an exception if it's not an ENTRY
                (throw (new IllegalArgumentException))))))))))

(defn- call-handler
  "Takes care of running an operation over binary file agent"
  [ binary-file function ]
  (let [; create empty journal
        pointer-journal          (file-safe-create-empty (:name-journal binary-file))
        binary-file-with-journal (assoc binary-file :pointer-journal pointer-journal)
        ; call the function
        binary-file-altered      (function binary-file-with-journal) ]
    ; write commit message to the log
    (write-entry-to-journal binary-file-with-journal journal-entry-type-commit 0 journal-dummy-data) 
    ; write changes into the binary file
    (save-changes binary-file-with-journal)
    ; close the journal file
    (file-safe-close pointer-journal)
    ; return new value for the agent
    (assoc binary-file-altered :pointer-journal nil)))

(defn call
  "Calls an operation on the binary file agent
   Arguments:
     - binary file agent
     - function to be called on the binary file"
  [ file-agent function ]
  (send-off file-agent call-handler function)) 

(defn- error-handler-func 
  "Will be called when an exception is thrown in either of the files. 
   Calls the callback method given to agent."
  [the-agent the-err]
  ((:error-callback @the-agent) the-err))

(defn #^clojure.lang.Agent open
  "Opens or creates given storage file, returns an instance of BinaryFile
   structure wrapped in an agent.
   Arguments:
     - path to the storage file
     - path to the journal file
     - callback function in case of exception"
  [ #^String file-storage #^String file-journal error-callback ]
  (let [ pointer-file   (file-safe-open file-storage)
         file-agent     (agent 
                          (BinaryFile. 
                            file-storage
                            file-journal
                            pointer-file
                            nil
                            (/ (. pointer-file length)
                               binaryfile-entry-length-aligned)
                            error-callback
                            (atom {}))) ]
    ; set error handler
    (set-error-handler! file-agent error-handler-func)
    ; check the journal
    (send-off file-agent 
              #(let [ pointer-journal (file-safe-open (:name-journal %)) ]
                 (save-changes (assoc % :pointer-journal pointer-journal))
                 (file-safe-close pointer-journal)
                 %))
    file-agent))
