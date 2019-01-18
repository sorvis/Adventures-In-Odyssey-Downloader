using System;

namespace Odyssey_Downloader
{
    internal class MultiDownload
    {
        public MultiDownload(Config settings, ref ProcessFile downloadAndSave, int limit)
        {
            int counter = 0;
            bool runLoop = true;
            while (runLoop)
            {
                GetFileInfo currentFile = new GetFileInfo(settings, counter);
                if (currentFile.GetFileUrl() != "" && counter < limit)
                {
                    Console.WriteLine("Downloading: " + currentFile.GetFullTitle());
                    downloadAndSave.Download(currentFile);
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