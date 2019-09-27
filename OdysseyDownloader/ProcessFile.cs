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
            _fullPath = string.IsNullOrWhiteSpace(settings.FullPathToFiles) ? "." : settings.FullPathToFiles;
            _indexFileName = settings.IndexFileName;
            _indexReader = indexReader;
            _downloader = downloader;
        }

        public bool Download(IFileInfo file)
        {
            if (_indexReader.HasTitle(file.Title))
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
                string filePath = Path.Combine(_fullPath, file.FileName);
                _downloader.GetFile(file.FileUrl, filePath);
                _indexReader.WriteToIndex(new Model.AudioFile(file));
                return true;
            }
        }
    }
}
