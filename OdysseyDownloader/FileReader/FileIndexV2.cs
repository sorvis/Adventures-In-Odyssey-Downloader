using Newtonsoft.Json;
using NLog;
using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System;
using System.Collections.Generic;
using System.IO;

namespace Adventures_In_Odyssey_Downloader.FileReaderV2
{
    public class FileIndexV2 : IIndexReader
    {
        private readonly Logger _logger = LogManager.GetCurrentClassLogger();
        private Config _config;

        public FileIndexV2(Config config)
        {
            _config = config;
        }

        public bool IndexDetected()
        {
            var indexLocation = _config.GetIndexFilePath();
            if (!File.Exists(indexLocation)) return false;
            var fileText = File.ReadAllText(indexLocation);
            try
            {
                dynamic index = JsonConvert.DeserializeObject(fileText);
                return index.Version == "V2";
            }
            catch (Exception exception)
            {
                _logger.Error(exception,
                    $"Unable to read contents of index file at: {indexLocation}. Probably this is the an older index reader checking for compatibility.");
                return false;
            }
        }
        public IEnumerable<AudioFile> ReadIndex()
        {
            var path = _config.GetIndexFilePath();
            var file = File.ReadAllText(path);
            var index = JsonConvert.DeserializeObject<Index>(file);
            return index.Files;
        }

        public IEnumerable<AudioFile> RebuildIndex()
        {
            List<AudioFile> audioFiles = new List<AudioFile>();
            List<string> titleList = new List<string>();
            foreach (string item in getFileNamesInDir())
            {
                var justFileName = Path.GetFileName(item);
                var title = justFileName.Replace($"{_config.FileExtension}", "").Replace('_', ' ');
                titleList.Add(title);
                audioFiles.Add(new AudioFile
                {
                    Title = title,
                    FileName = justFileName,
                });
            }

            writeIndex(audioFiles);
            return audioFiles;
        }

        private void writeIndex(List<AudioFile> fileLines)
        {
            var index = new Index { Files = fileLines };
            var indexJson = JsonConvert.SerializeObject(index);
            File.WriteAllText(_config.GetIndexFilePath(), indexJson);
        }

        private List<string> getFileNamesInDir()
        {
            var dir = _config.FullPathToFiles;
            var extension = _config.FileExtension;
            List<string> list = new List<string>();

            // Process the list of files found in the directory.
            foreach (string fileName in Directory.GetFiles(dir))
            {
                // do something with fileName
                if (fileName.Contains(extension))
                {
                    list.Add(fileName);
                }
            }
            list.Sort();
            return list;
        }

        public void WriteToIndex(AudioFile fileInfo)
        {
            throw new System.NotImplementedException();
        }

        public class Index
        {
            public IEnumerable<AudioFile> Files { get; set; }
        }
    }
}