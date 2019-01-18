using System.Collections.Specialized;
using System.Text.RegularExpressions;

namespace Odyssey_Downloader
{
    public class Arguments
    {
        // Variables

        private StringDictionary Parameters;

        // Constructor

        public Arguments(string[] arguments)
        {
            Parameters = new StringDictionary();
            Regex Spliter = new Regex(@"^-{1,2}|^/|=|:",
                RegexOptions.IgnoreCase | RegexOptions.Compiled);

            Regex Remover = new Regex(@"^['""]?(.*?)['""]?$",
                RegexOptions.IgnoreCase | RegexOptions.Compiled);

            string Parameter = null;
            string[] parts;

            // Valid parameters forms:

            // {-,/,--}param{ ,=,:}((",')value(",'))

            // Examples:

            // -param1 value1 --param2 /param3:"Test-:-work"

            //   /param4=happy -param5 '--=nice=--'

            foreach (string item in arguments)
            {
                // Look for new parameters (-,/ or --) and a

                // possible enclosed value (=,:)

                parts = Spliter.Split(item, 3);

                switch (parts.Length)
                {
                    // Found a value (for the last parameter

                    // found (space separator))

                    case 1:
                        if (Parameter != null)
                        {
                            if (!Parameters.ContainsKey(Parameter))
                            {
                                parts[0] =
                                    Remover.Replace(parts[0], "$1");

                                Parameters.Add(Parameter, parts[0]);
                            }
                            Parameter = null;
                        }
                        // else Error: no parameter waiting for a value (skipped)

                        break;

                    // Found just a parameter

                    case 2:
                        // The last parameter is still waiting.

                        // With no value, set it to true.

                        if (Parameter != null)
                        {
                            if (!Parameters.ContainsKey(Parameter))
                            {
                                Parameters.Add(Parameter, "true");
                            }
                        }
                        Parameter = parts[1];
                        break;

                    // Parameter with enclosed value

                    case 3:
                        // The last parameter is still waiting.

                        // With no value, set it to true.

                        if (Parameter != null)
                        {
                            if (!Parameters.ContainsKey(Parameter))
                            {
                                Parameters.Add(Parameter, "true");
                            }
                        }

                        Parameter = parts[1];

                        // Remove possible enclosing characters (",')

                        if (!Parameters.ContainsKey(Parameter))
                        {
                            parts[2] = Remover.Replace(parts[2], "$1");
                            Parameters.Add(Parameter, parts[2]);
                        }

                        Parameter = null;
                        break;
                }
            }
            // In case a parameter is still waiting

            if (Parameter != null)
            {
                if (!Parameters.ContainsKey(Parameter))
                {
                    Parameters.Add(Parameter, "true");
                }
            }
        }

        // Retrieve a parameter value if it exists

        // (overriding C# indexer property)

        public string this[string Param] => (Parameters[Param]);
    }
}