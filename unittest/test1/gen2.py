import random

k = 5
n = 10000000

def f(a,b):
  return random.randrange(a,b)

for i in range(n):
  print(str(k*i) + "," + str(f(1,50001)) + "," + str(f(1,5)) + "," + str(f(1,500001)) + ",2010")
