using System;

namespace Odyssey_Downloader
{
    internal class MultiDownload
    {
        private readonly Config _settings;
        private readonly ProcessFile _downloadAndSave;

        public MultiDownload(Config settings, ProcessFile downloadAndSave)
        {
            _settings = settings;
            _downloadAndSave = downloadAndSave;
        }

        public void Run(int limit)
        {
            int counter = 0;
            bool runLoop = true;
            while (runLoop)
            {
                GetFileInfo currentFile = new GetFileInfo(_settings, counter);
                if (currentFile.FileUrl!= "" && counter < limit)
                {
                    Console.WriteLine("Downloading: " + currentFile.FullTitle);
                    _downloadAndSave.Download(currentFile);
                }
                else
                {
                    runLoop = false;
                }
                counter++;
            }
        }
    }
}