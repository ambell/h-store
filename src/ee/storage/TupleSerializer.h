/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
#ifndef TUPLESERIALIZER_H_
#define TUPLESERIALIZER_H_

#include "common/TupleSchema.h"
#include "common/tabletuple.h"
#include "common/serializeio.h"

/**
 * Base class for tuple serializers
 */
namespace voltdb {
class TupleSerializer {
public:
    /**
     * Serialize the provided tuple to the provide serialize output
     */
    virtual void serializeTo(TableTuple tuple, ReferenceSerializeOutput *out) = 0;

    /**
     * Calculate the maximum size of a serialized tuple based upon the schema of the table/tuple
     */
    virtual int getMaxSerializedTupleSize(const TupleSchema *schema) = 0;

    virtual ~TupleSerializer() {}
};
}
#endif /* TUPLESERIALIZER_H_ */
