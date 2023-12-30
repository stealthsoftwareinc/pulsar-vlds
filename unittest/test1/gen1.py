import random

degrees = ["1", "2", "3", "4", "5"]
schools = [
  "University of Virginia",
  "William & Mary",
  "Virginia Tech",
  "George Mason University",
  "Virginia Commonwealth University"
]
sexes = ["M", "F", "X"]

k = 3
n = 10000000

def f(a,b):
  return random.randrange(a,b)

for i in range(n):
  print(str(k*i) + "," + degrees[f(0,len(degrees))] + "," + schools[f(0,len(schools))] + "," + sexes[f(0,len(sexes))] + ",2010")
