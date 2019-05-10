using System;
using System.IO;

namespace Odyssey_Downloader
{
    public class ProcessFile
    {
        protected string _fullPath;
        protected string _indexFileName;
        private readonly IIndexReader _indexReader;
        private readonly IDownloader _downloader;

        public ProcessFile(Config settings, IIndexReader indexReader, IDownloader downloader)
        {
            _fullPath = settings.FullPathToFiles;
            _indexFileName = settings.IndexFileName;
            _indexReader = indexReader;
            _downloader = downloader;
        }

        public bool Download(IFileInfo file)
        {
            if (checkFileList(file.Title))
            {
                Console.WriteLine("Skipping download already have file.");
                return false;
            }
            if(string.IsNullOrEmpty(file.FileUrl) 
                || file.Title.Contains("Free Online Christian Ministry Radio Broadcasts"))
            {
                return false;
            }
            else
            {
                string filePath = _fullPath + file.FileName;
                _downloader.GetFile(file.FileUrl, filePath);
                _indexReader.WriteToIndex(new Model.AudioFile { Title = file.Title });
                return true;
            }
        }

        private bool checkFileList(string title)
        {
            bool foundInIndex = false;

            int counter = 0;
            string line;

            var fileName = _fullPath + _indexFileName;
            if (!File.Exists(fileName)) return false;

            // Read the file and display it line by line.
            StreamReader file = new StreamReader(fileName);
            while ((line = file.ReadLine()) != null)
            {
                if (title == line)
                {
                    foundInIndex = true;
                }
                counter++;
            }
            file.Close();

            return foundInIndex;
        }
    }
}
